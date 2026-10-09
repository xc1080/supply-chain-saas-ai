"""Account recovery uses real isolated Redis and a separate fake Java authority."""
from __future__ import annotations

import json
import time
import unittest
from unittest.mock import AsyncMock, patch

from fastapi import HTTPException
from starlette.websockets import WebSocketDisconnect

import store_api
from commerce_api import owner_id
from customer_identity import CustomerIdentityStore
from shared_store import RedisSharedStore
import test_store_api as fixtures


class CustomerIdentityIntegrationTests(unittest.TestCase):
    setUp = fixtures.StoreIntegrationTests.setUp
    login = fixtures.StoreIntegrationTests.login
    api = fixtures.StoreIntegrationTests.api
    data = fixtures.StoreIntegrationTests.data
    add_cart = fixtures.StoreIntegrationTests.add_cart
    cart = fixtures.StoreIntegrationTests.cart
    post_order = fixtures.StoreIntegrationTests.post_order
    create_order = fixtures.StoreIntegrationTests.create_order
    detail = fixtures.StoreIntegrationTests.detail
    send_question = fixtures.StoreIntegrationTests.send_question
    await_message = fixtures.StoreIntegrationTests.await_message
    proposal = fixtures.StoreIntegrationTests.proposal

    def register(self, client, name='buyer_alice', password='customer-password-123', **extra):
        return self.data(client, 'account/register', userName=name, password=password, nickName='客户 Alice', **extra)

    def sign_in(self, client, name='buyer_alice', password='customer-password-123', **extra):
        return self.api(client, 'account/login', userName=name, password=password, **extra)

    def test_guest_upgrade_rotates_cookie_and_preserves_cart_orders_address_and_assistant(self):
        old_cookie = self.alice.cookies.get(store_api.COOKIE)
        self.add_cart(self.alice)
        order_id = self.create_order(self.alice)
        user = self.register(self.alice)
        self.assertEqual(user['identityType'], 'ACCOUNT')
        self.assertNotEqual(old_cookie, self.alice.cookies.get(store_api.COOKIE))
        self.assertEqual(self.detail(self.alice, order_id)['orderId'], order_id)
        self.assertEqual(self.cart(self.alice)[0]['productId'], 'lamp')
        self.assertEqual(self.data(self.alice, 'userAddress/loadDataList')[0]['addressId'], 'demo-address')
        # All existing Java mutations still sign the original SHA-256 owner.
        paid = self.data(self.alice, 'order/sandboxPay', orderId=order_id, scenario='success', paymentRequestId='stable-payment')
        self.assertEqual(paid['orderStatus'], 1)
        owner = self.authority.orders[order_id]['ownerId']
        self.assertEqual(owner, owner_id(old_cookie))
        mid = self.send_question(self.alice, '我有什么订单')
        self.await_message(self.alice, mid)
        self.alice.cookies.clear()
        self.alice.cookies.set(store_api.COOKIE, old_cookie)
        self.assertEqual(self.api(self.alice, 'account/getUserInfo')['code'], 901)

    def test_logout_and_cross_device_login_restore_orders_with_new_bearer(self):
        order_id = self.create_order(self.alice)
        self.register(self.alice)
        first_bearer = self.alice.cookies.get(store_api.COOKIE)
        self.data(self.alice, 'account/logout')
        self.bob.cookies.clear()
        self.assertEqual(self.sign_in(self.bob)['code'], 200)
        self.assertNotEqual(self.bob.cookies.get(store_api.COOKIE), first_bearer)
        self.assertEqual(self.detail(self.bob, order_id)['orderId'], order_id)
        self.alice.cookies.set(store_api.COOKIE, first_bearer)
        self.assertEqual(self.api(self.alice, 'account/getUserInfo')['code'], 901)
        new_bearer = self.bob.cookies.get(store_api.COOKIE)
        self.assertEqual(self.sign_in(self.bob)['code'], 200)
        self.assertNotEqual(new_bearer, self.bob.cookies.get(store_api.COOKIE))
        self.alice.cookies.clear()
        self.alice.cookies.set(store_api.COOKIE, new_bearer)
        self.assertEqual(self.api(self.alice, 'account/getUserInfo')['code'], 901)

    def test_redis_state_loss_relogin_restores_java_owner_but_old_guest_is_not_reimported(self):
        guest = self.alice.cookies.get(store_api.COOKIE)
        order_id = self.create_order(self.alice)
        self.register(self.alice)
        # A pre-Redis legacy row must never resurrect a bound guest credential.
        with store_api.db() as connection:
            connection.execute('INSERT INTO sessions(id,expires) VALUES(?,?)', (guest, time.time()+300))
        own_keys = list(self.redis.scan_iter(match=self.redis_prefix+':*'))
        self.redis.delete(*own_keys)
        self.alice.cookies.clear()
        self.alice.cookies.set(store_api.COOKIE, guest)
        self.assertEqual(self.api(self.alice, 'account/getUserInfo')['code'], 901)
        self.alice.cookies.clear()
        self.assertEqual(self.sign_in(self.alice)['code'], 200)
        self.assertEqual(self.detail(self.alice, order_id)['orderId'], order_id)

    def test_committed_registration_with_failed_redis_rotation_rejects_old_guest_and_recovers_account(self):
        guest = self.alice.cookies.get(store_api.COOKIE)
        self.add_cart(self.alice)
        order_id = self.create_order(self.alice)
        addresses = self.data(self.alice, 'userAddress/loadDataList')
        # SQLite has committed the account; the first Redis rotation then fails.
        with patch.object(RedisSharedStore, 'ensure_identity', new=AsyncMock(
                side_effect=HTTPException(503, '会话服务暂时不可用，请稍后重试'))):
            response = self.alice.post('/api/account/register', json={
                'userName':'rotation_fault_buyer', 'password':'customer-password-123', 'nickName':'故障窗口客户'})
        self.assertEqual((response.status_code, response.json()['code']), (503, 503))
        with store_api.db() as connection:
            row = dict(connection.execute('SELECT * FROM customer_accounts WHERE login_name=?',
                                         ('rotation_fault_buyer',)).fetchone())
        self.assertEqual(row['owner_seed'], guest)
        # The old Redis GUEST hash remains. Recovery must reject its credential
        # even before successful login has a chance to turn it into STATE.
        self.assertEqual(self.redis.hget(self.state_keys(guest)[0], 'kind'), None)
        self.assertEqual(self.api(self.alice, 'account/getUserInfo')['code'], 901)
        self.assertEqual(self.api(self.alice, 'order/getMyOrderDetail', orderId=order_id)['code'], 901)
        self.assertEqual(self.api(self.alice, 'order/sandboxPay', orderId=order_id,
                                  scenario='success', paymentRequestId='revoked-guest-attempt')['code'], 901)
        self.assertFalse(self.authority.payments)
        self.data(self.alice, 'account/logout')
        self.assertEqual(len(self.redis.hvals(self.state_keys(guest)[2])), 1)
        self.assertEqual(self.sign_in(self.bob, 'rotation_fault_buyer')['code'], 200)
        self.assertEqual(self.detail(self.bob, order_id)['orderId'], order_id)
        self.assertEqual(self.cart(self.bob)[0]['productId'], 'lamp')
        self.assertEqual(self.data(self.bob, 'userAddress/loadDataList'), addresses)
        self.alice.cookies.set(store_api.COOKIE, guest)
        self.assertEqual(self.api(self.alice, 'account/getUserInfo')['code'], 901)

    def test_account_reference_loss_never_downgrades_bearer_into_guest(self):
        order_id = self.create_order(self.alice)
        self.register(self.alice)
        cookie = self.alice.cookies.get(store_api.COOKIE)
        key = self.state_keys(cookie)[0]
        self.assertEqual(self.redis.hget(key, 'kind'), 'ACCOUNT')
        self.redis.hdel(key, 'account_id', 'auth_version')
        self.assertGreater(self.redis.ttl(key), 0)
        self.assertEqual(self.api(self.alice, 'account/getUserInfo')['code'], 901)
        self.assertEqual(self.api(self.alice, 'order/getMyOrderDetail', orderId=order_id)['code'], 901)
        self.assertEqual(self.api(self.alice, 'order/sandboxPay', orderId=order_id,
                                  scenario='success', paymentRequestId='lost-reference-attempt')['code'], 901)
        self.assertFalse(self.authority.payments)
        self.assertEqual(self.sign_in(self.bob)['code'], 200)
        self.assertEqual(self.detail(self.bob, order_id)['orderId'], order_id)

    def test_cannot_claim_foreign_owner_via_client_params_and_account_switch_does_not_merge(self):
        alice_order = self.create_order(self.alice, 'alice-order')
        self.register(self.alice)
        bob_cookie = self.bob.cookies.get(store_api.COOKIE)
        bob_order = self.create_order(self.bob, 'bob-order')
        alice_cookie = self.alice.cookies.get(store_api.COOKIE)
        # Client-supplied owner/session IDs do not participate in registration.
        self.register(self.bob, 'buyer_bob', ownerId=owner_id(alice_cookie), sessionId=alice_cookie)
        self.assertEqual(self.detail(self.bob, bob_order)['orderId'], bob_order)
        self.assertEqual(self.api(self.bob, 'order/getMyOrderDetail', orderId=alice_order)['code'], 404)
        self.assertEqual(self.sign_in(self.bob)['code'], 200)
        self.assertEqual(self.detail(self.bob, alice_order)['orderId'], alice_order)
        self.assertEqual(self.api(self.bob, 'order/getMyOrderDetail', orderId=bob_order)['code'], 404)
        self.assertNotEqual(owner_id(bob_cookie), self.authority.orders[alice_order]['ownerId'])

    def test_bad_password_unknown_user_and_disabled_account_do_not_create_visitor_login(self):
        user = self.register(self.alice)
        before = self.bob.cookies.get(store_api.COOKIE)
        for name, password in [('buyer_alice', 'incorrect-password'), ('unknown_user', 'incorrect-password')]:
            result = self.sign_in(self.bob, name, password)
            self.assertEqual((result['code'], result['info']), (403, '账号或密码不正确'))
            self.assertEqual(self.bob.cookies.get(store_api.COOKIE), before)
        with store_api.db() as connection:
            connection.execute('UPDATE customer_accounts SET enabled=0 WHERE id=?', (user['userId'],))
        self.assertEqual(self.api(self.alice, 'account/getUserInfo')['code'], 901)
        self.assertEqual(self.sign_in(self.bob)['code'], 403)
        self.assertEqual(self.api(self.alice, 'account/logout')['code'], 200)
        self.assertIsNone(self.alice.cookies.get(store_api.COOKIE))

    def test_namespace_password_storage_and_owner_seed_are_not_bearer_tokens(self):
        user = self.register(self.alice)
        with store_api.db() as connection:
            row = dict(connection.execute('SELECT * FROM customer_accounts WHERE id=?', (user['userId'],)).fetchone())
        self.assertNotIn(row['owner_seed'], json.dumps(user))
        self.assertTrue(bytes(row['password_hash']).startswith(b'$2b$12$'))
        for tenant, shop in [('studio','default'), ('demo','other-shop')]:
            identities = CustomerIdentityStore(store_api.db, tenant, shop)
            with self.assertRaises(HTTPException) as raised:
                identities.login('buyer_alice', 'customer-password-123')
            self.assertEqual(raised.exception.status_code, 403)
            with self.assertRaises(HTTPException):
                identities.account(user['userId'], 1)
        self.bob.cookies.clear()
        self.bob.cookies.set(store_api.COOKIE, row['owner_seed'])
        self.assertEqual(self.api(self.bob, 'account/getUserInfo')['code'], 901)

    def test_post_only_login_registration_logout_and_rate_limit(self):
        for path in ['account/login','account/register','account/logout']:
            self.assertEqual(self.alice.get('/api/'+path).json()['code'], 405)
        for _ in range(12):
            self.assertEqual(self.sign_in(self.bob, 'nonexistent', 'invalid-password-123')['code'], 403)
        self.assertEqual(self.sign_in(self.bob, 'nonexistent', 'invalid-password-123')['code'], 429)

    def test_registration_validation_and_active_account_cannot_be_rebound(self):
        for name, password in [('bad','long-enough-password'), ('valid_user','short'), ('valid_user','中文'*30)]:
            self.assertEqual(self.api(self.alice, 'account/register', userName=name, password=password)['code'], 422)
        self.register(self.alice)
        self.assertEqual(self.api(self.alice, 'account/register', userName='another_user', password='long-enough-password')['code'], 409)

    def test_expired_guest_is_not_silently_rebound_to_a_fresh_identity(self):
        guest = self.alice.cookies.get(store_api.COOKIE)
        self.redis.delete(self.state_keys(guest)[0])
        result = self.api(self.alice, 'account/register', userName='visitor_buyer', password='long-enough-password')
        self.assertEqual(result['code'], 901)
        with store_api.db() as connection:
            self.assertEqual(connection.execute('SELECT COUNT(*) FROM customer_accounts').fetchone()[0], 0)

    def test_account_websocket_uses_durable_message_identity_and_logout_revokes_connection(self):
        self.register(self.alice)
        with self.alice.websocket_connect('ws://127.0.0.1:7050/ws/', headers={'origin': 'http://127.0.0.1:6001'}) as socket:
            socket.send_text('ping')
            self.assertEqual(socket.receive_text(), 'pong')
            mid = self.send_question(self.alice, '灯具是否需要网关')
            result = socket.receive_json()
            self.assertEqual(result['messageId'], mid)
            self.data(self.alice, 'account/logout')
            with self.assertRaises(WebSocketDisconnect):
                socket.receive_text()


if __name__ == '__main__':
    unittest.main()
