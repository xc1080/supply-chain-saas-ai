"""Real Redis checks; run with the local project Redis or FUSION_REDIS_URL.

Each test owns a random namespace. No FLUSHDB, application keys or real cookies
are touched. The two clients represent independent facade instances.
"""
from __future__ import annotations

import asyncio
import json
import os
import secrets
import sqlite3
import tempfile
import threading
import time
import unittest
from contextlib import closing
from pathlib import Path

from fastapi import HTTPException
from shared_store import RedisSharedStore, SESSION_TTL


class SharedStoreTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="fusion-shared-state-")
        self.path = Path(self.temp.name) / "legacy.sqlite3"
        self.prefix = "fusion:test:shared:" + secrets.token_hex(10)
        self.first = RedisSharedStore(self.path, tenant="demo", shop="default", prefix=self.prefix)
        self.second = RedisSharedStore(self.path, tenant="demo", shop="default", prefix=self.prefix)
        self.stores = [self.first, self.second]
        self.client = await self.first.get_client()
        await self.client.ping()

    async def asyncTearDown(self):
        keys = [key async for key in self.client.scan_iter(match=self.prefix + ":*")]
        if keys:
            await self.client.delete(*keys)
        for store in self.stores:
            await store.close()
        self.temp.cleanup()

    def legacy(self, *, expires=None):
        session = secrets.token_hex(24)
        with closing(sqlite3.connect(self.path)) as connection, connection:
            connection.executescript("""
                CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY,expires REAL,context_product TEXT,keywords TEXT);
                CREATE TABLE IF NOT EXISTS carts(id TEXT PRIMARY KEY,session_id TEXT,product_id TEXT,quantity INTEGER);
            """)
            connection.execute("INSERT INTO sessions VALUES(?,?,?,?)", (session, expires or time.time() + 120, "lamp", '["Zigbee"]'))
            connection.execute("INSERT INTO carts VALUES(?,?,?,?)", ("legacy-cart", session, "lamp", 2))
        return session

    async def test_two_instances_share_cookie_cart_keywords_and_context(self):
        session = await self.first.create_session()
        self.assertEqual(await self.second.valid_session(session), session)
        await self.first.add_cart(session, "lamp", 2, 100)
        self.assertEqual((await self.second.carts(session))[0]["quantity"], 2)
        await self.second.context(session, "lamp", update=True)
        self.assertEqual(await self.first.context(session), "lamp")
        await self.first.keywords(session, "save", "Zigbee")
        self.assertEqual(await self.second.keywords(session), ["Zigbee"])
        await self.second.keywords(session, "clear")
        self.assertEqual(await self.first.keywords(session), [])

    async def test_concurrent_cart_increments_and_decrements_are_atomic(self):
        session = await self.first.create_session()
        await asyncio.gather(*(self.stores[index % 2].add_cart(session, "lamp", 1, 100) for index in range(100)))
        row = (await self.first.carts(session))[0]
        self.assertEqual(row["quantity"], 100)
        identity = row["id"]
        with self.assertRaises(HTTPException) as raised:
            await self.second.add_cart(session, "lamp", 1, 100)
        self.assertEqual(raised.exception.status_code, 409)
        await asyncio.gather(*(self.stores[index % 2].add_cart(session, "lamp", -1, 100) for index in range(50)))
        row = (await self.second.carts(session))[0]
        self.assertEqual((row["quantity"], row["id"]), (50, identity))

    async def test_cart_delete_is_owner_scoped(self):
        alice, bob = await self.first.create_session(), await self.second.create_session()
        await self.first.add_cart(alice, "lamp", 1, 100)
        identity = (await self.first.carts(alice))[0]["id"]
        await self.second.delete_cart(bob, identity)
        self.assertEqual(len(await self.first.carts(alice)), 1)
        await self.second.delete_cart(alice, identity)
        self.assertEqual(await self.first.carts(alice), [])

    async def test_tenant_and_shop_boundaries_reject_the_same_cookie(self):
        session = await self.first.create_session()
        for tenant, shop in (("studio", "default"), ("demo", "other-shop")):
            # Separate tenants use separate SQLite sources as well as Redis keys.
            store = RedisSharedStore(self.path.parent / (tenant + "-other.sqlite3"), tenant=tenant, shop=shop, prefix=self.prefix)
            self.stores.append(store)
            self.assertIsNone(await store.valid_session(session))
            with self.assertRaises(HTTPException) as raised:
                await store.add_cart(session, "lamp", 1, 100)
            self.assertEqual(raised.exception.status_code, 401)

    async def test_legacy_cart_import_is_once_and_deleted_items_do_not_return(self):
        session = self.legacy()
        self.assertEqual(await self.first.valid_session(session), session)
        self.assertEqual(await self.second.valid_session(session), session)
        self.assertEqual((await self.first.carts(session))[0]["quantity"], 2)
        self.assertEqual(await self.second.keywords(session), ["Zigbee"])
        self.assertEqual(await self.second.context(session), "lamp")
        await self.second.delete_cart(session, "legacy-cart")
        self.assertEqual(await self.first.carts(session), [])
        # Even a lost/expired shared session must not re-import its old cart.
        await self.client.delete(self.first.keys(session)[0])
        self.assertIsNone(await self.second.valid_session(session))
        self.assertEqual(await self.client.hlen(self.first.keys(session)[2]), 0)

    async def test_parallel_legacy_import_never_overwrites_shared_cart(self):
        session = self.legacy()
        values = await asyncio.gather(*(self.stores[index % 2].valid_session(session) for index in range(30)))
        self.assertEqual(set(values), {session})
        self.assertEqual((await self.first.carts(session))[0]["quantity"], 2)
        await self.second.add_cart(session, "lamp", 1, 100)
        self.assertEqual(await self.first.valid_session(session), session)
        self.assertEqual((await self.first.carts(session))[0]["quantity"], 3)

    async def test_logout_while_import_reads_sqlite_cannot_resurrect_session(self):
        session = self.legacy()
        snapshot_ready, release_snapshot = threading.Event(), threading.Event()
        original = self.first._legacy_snapshot

        def delayed_snapshot(value):
            result = original(value)
            snapshot_ready.set()
            release_snapshot.wait(timeout=5)
            return result

        self.first._legacy_snapshot = delayed_snapshot
        importing = asyncio.create_task(self.first.valid_session(session))
        self.assertTrue(await asyncio.to_thread(snapshot_ready.wait, 3))
        try:
            await self.second.logout(session)
        finally:
            release_snapshot.set()
        self.assertIsNone(await importing)
        self.assertIsNone(await self.second.valid_session(session))
        self.assertEqual(await self.client.get(self.first.keys(session)[1]), "revoked")
        self.assertEqual(await self.client.exists(self.first.keys(session)[2]), 0)

    async def test_legacy_cookie_is_not_imported_into_another_shop(self):
        session = self.legacy()
        other = RedisSharedStore(self.path, tenant="demo", shop="other-shop", prefix=self.prefix)
        self.stores.append(other)
        self.assertIsNone(await other.valid_session(session))
        self.assertEqual(await self.first.valid_session(session), session)

    async def test_logout_revocation_and_expiry_are_shared(self):
        session = await self.first.create_session()
        keys = self.first.keys(session)
        ttl = await self.client.ttl(keys[0])
        self.assertGreater(ttl, SESSION_TTL - 5)
        await self.first.add_cart(session, "lamp", 1, 100)
        await self.second.logout(session)
        self.assertIsNone(await self.first.valid_session(session))
        self.assertEqual(await self.client.exists(keys[0], keys[2]), 0)
        self.assertGreater(await self.client.ttl(keys[1]), SESSION_TTL - 5)
        expired = self.legacy(expires=time.time() - 1)
        self.assertIsNone(await self.first.valid_session(expired))

    async def test_checkout_cart_cleanup_is_idempotent_and_preserves_later_additions(self):
        session = await self.first.create_session()
        await self.first.add_cart(session, "lamp", 3, 100)
        await self.second.consume_cart(session, "order-one", {"lamp": 2})
        self.assertEqual((await self.first.carts(session))[0]["quantity"], 1)
        await self.first.add_cart(session, "lamp", 2, 100)
        await self.second.consume_cart(session, "order-one", {"lamp": 2})
        self.assertEqual((await self.first.carts(session))[0]["quantity"], 3)

    async def test_redis_unavailable_fails_closed_even_with_valid_legacy_state(self):
        session = self.legacy()
        unavailable = RedisSharedStore(self.path, prefix=self.prefix, url="redis://127.0.0.1:1/0")
        try:
            for call in (unavailable.create_session, lambda: unavailable.valid_session(session),
                         lambda: unavailable.add_cart(session, "lamp", 1, 100)):
                with self.assertRaises(HTTPException) as raised:
                    await call()
                self.assertEqual(raised.exception.status_code, 503)
        finally:
            await unavailable.close()
        with closing(sqlite3.connect(self.path)) as connection:
            self.assertEqual(connection.execute("SELECT quantity FROM carts").fetchone()[0], 2)

    async def test_addresses_are_shared_atomic_and_preserve_a_single_default(self):
        session = await self.first.create_session()
        sample = await self.first.addresses(session)
        self.assertEqual(sample[0]["addressId"], "demo-address")
        def row(identity):
            return {"addressId": identity, "addressee": "练习用户", "phone": "+86 13800000000", "address": "上海市练习区模拟地址一号", "defaultType": 1}
        await asyncio.gather(*(self.stores[i % 2].addresses(session, "save", str(i), row(str(i))) for i in range(8)))
        rows = await self.second.addresses(session)
        self.assertEqual(len(rows), 9)
        self.assertEqual(sum(row["defaultType"] == 1 for row in rows), 1)
        await self.first.addresses(session, "default", "3")
        self.assertEqual((await self.second.addresses(session))[0]["addressId"], "3")
        snapshot = await self.second.shipping_address(session, "3")
        await self.first.addresses(session, "update", "3", {**row("3"), "address": "北京市另一个模拟地址"})
        self.assertEqual(snapshot["address"], "上海市练习区模拟地址一号")
        self.assertEqual(set(snapshot), {"addressee", "phone", "address"})

    async def test_addresses_are_owner_scoped_and_deleting_all_does_not_reseed(self):
        alice, bob = await self.first.create_session(), await self.second.create_session()
        row = {"addressId":"alice-only", "addressee":"练习收货人", "phone":"13800000000", "address":"练习园区虚构收货地址", "defaultType":1}
        await self.first.addresses(alice, "save", row["addressId"], row)
        with self.assertRaises(HTTPException) as failure:
            await self.second.addresses(bob, "update", row["addressId"], row)
        self.assertEqual(failure.exception.status_code, 404)
        with self.assertRaises(HTTPException): await self.second.shipping_address(bob, row["addressId"])
        for address in await self.first.addresses(alice):
            await self.first.addresses(alice, "delete", address["addressId"])
        self.assertEqual(await self.second.addresses(alice), [])
        await self.first.close()
        self.assertEqual(await self.first.addresses(alice), [])
        self.client = await self.first.get_client()
        tenant = RedisSharedStore(self.path, tenant="customer", shop="default", prefix=self.prefix)
        self.stores.append(tenant)
        self.assertEqual(await tenant.addresses(await tenant.create_session()), [])


if __name__ == "__main__":
    unittest.main()
