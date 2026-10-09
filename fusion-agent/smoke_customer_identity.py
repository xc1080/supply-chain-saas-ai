"""Local live guest -> account -> recovered order -> idempotent cancellation.

Creates two fictional customers and one unpaid order through business APIs.
Retains those records and their audit history. No payment, LLM call, SQL write,
stock reset or business-record deletion. Optional Redis recovery deletes only
the verified authentication hash of this run's own current account cookie.
"""
from __future__ import annotations

import argparse
from contextlib import ExitStack
from datetime import datetime, timezone
from decimal import Decimal
import json
import os
from pathlib import Path
import re
import secrets
from urllib.parse import urlparse
import uuid

import httpx
import redis

from shared_store import RedisSharedStore


JAVA = os.getenv('FUSION_JAVA_URL', 'http://127.0.0.1:8035').rstrip('/')
STORE = os.getenv('FUSION_SMOKE_STORE', 'http://127.0.0.1:7050').rstrip('/')
OTHER_STORE = os.getenv('FUSION_SMOKE_OTHER_STORE', 'http://127.0.0.1:7051').rstrip('/')
STOCK_FIELDS = ('bookStock', 'reservedStock', 'activityStock', 'unavailableStock', 'availableStock')
DEFAULT_RESULT = Path(__file__).resolve().parent.parent / 'logs' / 'customer-identity-result.json'


class SafeFailure(Exception):
    pass


class Acceptance:
    def __init__(self, *, simulate_session_loss=False, result_path=DEFAULT_RESULT):
        self.run_id = uuid.uuid4().hex[:12]
        self.simulate_session_loss = simulate_session_loss
        self.result_path = Path(result_path)
        self.report = {
            'runId': self.run_id, 'startedAt': datetime.now(timezone.utc).isoformat(),
            'status': 'RUNNING', 'checks': [], 'inventoryRestored': False,
            'auditDocumentsRetained': True, 'fictionalCustomersRetained': True,
            'paymentAttempted': False, 'modelCalled': False, 'sqlUsed': False,
            'redisAuthSessionDeleted': False,
        }

    def check(self, name, condition):
        self.report['checks'].append({'name': name, 'passed': bool(condition)})
        if not condition:
            raise SafeFailure(name)

    def request(self, client, name, method, path, body=None, *, expected=200):
        response = client.request(method, path, json=body)
        try:
            payload = response.json()
        except ValueError:
            payload = None
        code = payload.get('code') if isinstance(payload, dict) else None
        passed = type(code) is int and code == expected and response.status_code in (200, expected)
        self.report['checks'].append({'name': name, 'httpStatus': response.status_code,
                                      'code': code if type(code) is int else None,
                                      'expected': expected, 'passed': passed})
        if not passed:
            # Never include response bodies, credentials or request headers in reports.
            raise SafeFailure(name)
        return payload

    def api(self, client, name, method, path, body=None, *, expected=200):
        return self.request(client, name, method, path, body, expected=expected).get('data')

    def call(self, client, name, path, *, expected=200, **body):
        return self.api(client, name, 'POST', '/api/'+path, body, expected=expected)

    def client(self, stack, base):
        return stack.enter_context(httpx.Client(base_url=base, timeout=30, trust_env=False))

    def visitor(self, client, name):
        user = self.api(client, name+'.bootstrap', 'GET', '/api/account/autoLogin')
        self.check(name+'.guest', user.get('identityType') == 'GUEST')
        names = list(client.cookies.keys())
        self.check(name+'.singleStoreCookie', len(names) == 1)
        return user, names[0], client.cookies.get(names[0])

    def stock(self, admin, pid, name):
        rows = self.api(admin, name, 'GET', '/commerce/inventory')
        match = next((row for row in rows if str(row['productId']) == str(pid)), None)
        self.check(name+'.found', match is not None)
        return {field: str(Decimal(str(match[field]))) for field in STOCK_FIELDS}

    def stock_ledger(self, admin, pid, order_id, name):
        rows = self.api(admin, name, 'GET', f'/commerce/inventory/{pid}/ledger?limit=200')
        return [row for row in rows if row.get('order_id') == order_id]

    def lose_auth_session(self, cookie_name, client, user):
        raw_cookie = client.cookies.get(cookie_name)
        self.check('ownAuthCookieFormat', bool(raw_cookie and re.fullmatch(r'[a-f0-9]{48}', raw_cookie)))
        url = os.getenv('FUSION_REDIS_URL', 'redis://127.0.0.1:6379/0')
        self.check('localRedisOnly', urlparse(url).hostname in {'127.0.0.1', 'localhost', '::1'})
        namespace = RedisSharedStore(Path('unused.sqlite3'), tenant=user['tenantId'],
                                     shop=os.getenv('FUSION_SHOP_ID', 'default'), url=url)
        auth_key = namespace.keys(raw_cookie)[0]
        with redis.Redis.from_url(url, decode_responses=True) as client_redis:
            # Validate ownership and type before deletion. Never scan, flush,
            # delete a cart/STATE owner namespace, or touch another visitor.
            fields = client_redis.hmget(auth_key, 'kind', 'account_id')
            self.check('onlyThisRunAccountAuthHash', fields == ['ACCOUNT', user['userId']])
            self.check('oneOwnAuthHashDeleted', client_redis.delete(auth_key) == 1)
        self.report['redisAuthSessionDeleted'] = True

    def run(self):
        for base in (JAVA, STORE, OTHER_STORE):
            if urlparse(base).hostname not in {'127.0.0.1', 'localhost', '::1'}:
                raise SafeFailure('Identity acceptance is limited to local services')
        password = secrets.token_urlsafe(24)
        account_name, stranger_name = 'verify_'+self.run_id, 'verify_other_'+self.run_id
        self.report['fictionalAccountNames'] = [account_name, stranger_name]
        order_id, order_body, pid, before = None, None, None, None
        order_attempted, registration_attempted = False, False
        with ExitStack() as stack:
            admin = self.client(stack, JAVA)
            buyer, restored, stranger, foreign, old_browser = [self.client(stack, url)
                for url in (STORE, STORE, STORE, OTHER_STORE, STORE)]
            try:
                login = self.request(admin, 'merchant.login', 'POST', '/login', {
                    'username': os.getenv('FUSION_SMOKE_USER', 'admin'),
                    'password': os.getenv('FUSION_SMOKE_PASSWORD', 'admin123')})
                self.check('merchantTokenIssued', isinstance(login.get('token'), str) and bool(login['token']))
                admin.headers.update({'Authorization': 'Bearer '+login['token'],
                                      'X-Shop-ID': os.getenv('FUSION_SHOP_ID', 'default')})
                merchant = self.api(admin, 'merchant.context', 'GET', '/commerce/context')
                guest, cookie_name, guest_cookie = self.visitor(buyer, 'buyer')
                self.check('merchantStoreTenantMatches', merchant['tenantId'] == guest['tenantId'])
                self.report.update(tenantId=guest['tenantId'], shopId=os.getenv('FUSION_SHOP_ID', 'default'))
                products = self.api(buyer, 'publicCatalog', 'GET', '/api/product/loadProduct?pageSize=50')['list']
                product = next((row for row in products if row.get('minPrice') is not None
                                and Decimal(str(row.get('availableStock', 0))) >= 1), None)
                self.check('sellablePricedProductExists', product is not None)
                pid = str(product['productId'])
                self.report.update(productId=pid, productCode=product['productCode'])
                before = self.stock(admin, pid, 'stockBefore')
                self.report['stockBefore'] = before
                address = self.call(buyer, 'fictionalAddress', 'userAddress/addAddress',
                    addressee='身份恢复验收', phone='00000000000',
                    address='虚构地址：账户恢复练习空间，不寄送实物', defaultType=1)
                order_body = {'payMethod': 'demo', 'addressId': address['addressId'],
                    'clientRequestId': 'identity_'+self.run_id,
                    'orderList': [{'productId': pid, 'buyCount': 1, 'propertyValueIds': 'default'}]}
                order_attempted = True
                created = self.call(buyer, 'guestOrderCreated', 'order/postOrder', **order_body)
                order_id = created['orderId']
                self.report['orderId'] = order_id
                reserved = self.stock(admin, pid, 'stockReserved')
                self.check('onlyOneReservedNoPhysicalMovement',
                    Decimal(reserved['bookStock']) == Decimal(before['bookStock'])
                    and Decimal(reserved['reservedStock']) == Decimal(before['reservedStock'])+1
                    and Decimal(reserved['availableStock']) == Decimal(before['availableStock'])-1
                    and reserved['activityStock'] == before['activityStock']
                    and reserved['unavailableStock'] == before['unavailableStock'])
                registration_attempted = True
                account = self.call(buyer, 'guestRegistered', 'account/register', userName=account_name,
                                    password=password, nickName='虚构身份验收_'+self.run_id)
                self.check('persistentAccountIssued', account.get('identityType') == 'ACCOUNT'
                           and account.get('persistentIdentity') is True)
                account_cookie = buyer.cookies.get(cookie_name)
                self.check('guestCookieRotatedOnRegistration', account_cookie != guest_cookie)
                detail = self.call(buyer, 'registrationInheritedOrder', 'order/getMyOrderDetail', orderId=order_id)
                self.check('unpaidOrderRetained', detail['orderId'] == order_id and detail['orderStatus'] == 0)
                self.call(buyer, 'sameVisitorRegistrationConflict', 'account/register', expected=409,
                          userName='verify_again_'+self.run_id, password=password)
                old_browser.cookies.set(cookie_name, guest_cookie)
                self.call(old_browser, 'oldGuestCookieCannotRead', 'account/getUserInfo', expected=901)
                self.call(buyer, 'accountLogout', 'account/logout')
                self.check('logoutClearsBrowserCookie', buyer.cookies.get(cookie_name) is None)
                old_browser.cookies.clear()
                old_browser.cookies.set(cookie_name, account_cookie)
                self.call(old_browser, 'loggedOutCookieRejected', 'account/getUserInfo', expected=901)
                account_restored = self.call(restored, 'independentBrowserLogin', 'account/login',
                                             userName=account_name, password=password)
                self.check('sameDurableCustomerId', account_restored['userId'] == account['userId'])
                self.check('independentLoginNewCookie', restored.cookies.get(cookie_name) != account_cookie)
                detail = self.call(restored, 'crossDeviceOrderRecovered', 'order/getMyOrderDetail', orderId=order_id)
                self.check('crossDeviceOwnerPreserved', detail['orderId'] == order_id and detail['orderStatus'] == 0)
                orders = self.call(restored, 'recoveredOrderList', 'order/loadMyOrder', pageSize=50)['list']
                self.check('ownedOrderListedExactlyOnce', sum(row['orderId'] == order_id for row in orders) == 1)
                self.visitor(stranger, 'stranger')
                self.call(stranger, 'existingLoginNameConflict', 'account/register', expected=409,
                          userName=account_name, password=password)
                self.call(stranger, 'strangerAccountCreated', 'account/register',
                          userName=stranger_name, password=secrets.token_urlsafe(24), nickName='虚构陌生账户')
                self.call(stranger, 'foreignAccountCannotReadOrder', 'order/getMyOrderDetail', expected=404, orderId=order_id)
                self.call(stranger, 'foreignAccountCannotCancelOrder', 'order/cancelOrder', expected=404, orderId=order_id)
                foreign_user, foreign_cookie_name, _ = self.visitor(foreign, 'otherTenant')
                self.check('otherStoreReallyDifferentTenant', foreign_user['tenantId'] != guest['tenantId'])
                self.call(foreign, 'accountCannotLoginOtherTenant', 'account/login', expected=403,
                          userName=account_name, password=password)
                self.call(foreign, 'otherTenantCannotReadOrder', 'order/getMyOrderDetail', expected=404, orderId=order_id)
                foreign.cookies.clear()
                foreign.cookies.set(foreign_cookie_name, restored.cookies.get(cookie_name))
                self.call(foreign, 'copiedCookieCannotCrossTenant', 'account/getUserInfo', expected=901)
                prior_cookie = restored.cookies.get(cookie_name)
                self.call(restored, 'repeatLoginRotatesCookie', 'account/login', userName=account_name, password=password)
                self.check('loginRotatedAuthenticatedCookie', restored.cookies.get(cookie_name) != prior_cookie)
                old_browser.cookies.clear()
                old_browser.cookies.set(cookie_name, prior_cookie)
                self.call(old_browser, 'replacedLoginCookieRejected', 'account/getUserInfo', expected=901)
                if self.simulate_session_loss:
                    self.lose_auth_session(cookie_name, restored, account)
                    self.call(restored, 'lostRedisAuthSessionRejected', 'account/getUserInfo', expected=901)
                    restored.cookies.clear()
                    self.call(restored, 'loginAfterRedisAuthLoss', 'account/login', userName=account_name, password=password)
                    detail = self.call(restored, 'orderRecoveredAfterAuthLoss', 'order/getMyOrderDetail', orderId=order_id)
                    self.check('ownerStableAfterSessionLoss', detail['orderId'] == order_id and detail['orderStatus'] == 0)
                cancelled = self.call(restored, 'recoveredOwnerCancels', 'order/cancelOrder', orderId=order_id)
                self.check('orderCancelled', cancelled['orderStatus'] == 4)
                cancelled_again = self.call(restored, 'cancellationReplay', 'order/cancelOrder', orderId=order_id)
                self.check('cancellationIdempotent', cancelled_again['orderStatus'] == 4)
                after = self.stock(admin, pid, 'stockAfter')
                self.report['stockAfter'] = after
                self.check('allInventoryBalancesRestored', after == before)
                ledger = self.stock_ledger(admin, pid, order_id, 'retainedOrderStockLedger')
                reserves = [row for row in ledger if row['event_type'] == 'RESERVE']
                releases = [row for row in ledger if row['event_type'] == 'RELEASE']
                self.check('oneReserveOneReleaseOnly', len(ledger) == 2 and len(reserves) == 1 and len(releases) == 1
                           and Decimal(str(reserves[0]['delta_reserved'])) == 1
                           and Decimal(str(releases[0]['delta_reserved'])) == -1
                           and all(Decimal(str(row['delta_on_hand'])) == 0 for row in ledger))
                retained = self.call(restored, 'cancelledOrderRetainedForAudit', 'order/getMyOrderDetail', orderId=order_id)
                self.check('businessRecordRetained', retained['orderId'] == order_id and retained['orderStatus'] == 4)
                self.report.update(status='PASSED', inventoryRestored=True, reserveEntries=1, releaseEntries=1)
            except Exception as error:
                self.report.update(status='FAILED', errorType=type(error).__name__,
                    failedCheck=str(error) if isinstance(error, SafeFailure) else 'Diagnostic suppressed to protect credentials')
                # Restore only this run's own unpaid hold through the business API.
                # A response-loss retry retains the identical checkout request key.
                try:
                    cleanup_client = buyer
                    if registration_attempted:
                        login_response = restored.post('/api/account/login', json={'userName': account_name, 'password': password})
                        if login_response.json().get('code') == 200:
                            cleanup_client = restored
                    if order_id is None and order_attempted:
                        order_id = self.call(cleanup_client, 'failureRecoverExactCheckout', 'order/postOrder', **order_body)['orderId']
                        self.report['orderId'] = order_id
                    if order_id:
                        row = self.call(cleanup_client, 'failureReadOwnOrder', 'order/getMyOrderDetail', orderId=order_id)
                        if row['orderStatus'] == 0:
                            self.call(cleanup_client, 'failureCancelOwnUnpaidOrder', 'order/cancelOrder', orderId=order_id)
                        if before is not None:
                            after = self.stock(admin, pid, 'failureStockAfter')
                            self.report.update(stockAfter=after, inventoryRestored=after == before)
                except Exception as cleanup_error:
                    self.report['cleanupErrorType'] = type(cleanup_error).__name__
                    self.report['manualReviewRequired'] = True
            finally:
                self.report['finishedAt'] = datetime.now(timezone.utc).isoformat()
                self.report['passed'] = sum(check['passed'] for check in self.report['checks'])
                self.report['failed'] = len(self.report['checks']) - self.report['passed']
                self.result_path.parent.mkdir(parents=True, exist_ok=True)
                self.result_path.write_text(json.dumps(self.report, ensure_ascii=False, indent=2), encoding='utf-8')
        return self.report


def main():
    parser = argparse.ArgumentParser(description='Local guest/account order recovery acceptance; retains fictional audit data.')
    parser.add_argument('--simulate-session-loss', action='store_true', help='Delete only this run account authentication Redis hash.')
    parser.add_argument('--result', type=Path, default=DEFAULT_RESULT)
    options = parser.parse_args()
    acceptance = Acceptance(simulate_session_loss=options.simulate_session_loss, result_path=options.result)
    try:
        report = acceptance.run()
    except Exception as error:
        # Endpoint validation fails before any business write.
        print(json.dumps({'status': 'FAILED', 'errorType': type(error).__name__}, ensure_ascii=False))
        return 1
    print(json.dumps({key: report[key] for key in ('status', 'passed', 'failed', 'inventoryRestored', 'redisAuthSessionDeleted')}, ensure_ascii=False))
    return 0 if report['status'] == 'PASSED' else 1


if __name__ == '__main__':
    raise SystemExit(main())
