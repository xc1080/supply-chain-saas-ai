"""C-side checks with isolated Redis/SQLite state and a fake Java service."""

from __future__ import annotations

import asyncio
import copy
import json
import os
import secrets
import tempfile
import time
import unittest
from datetime import datetime, timedelta
from decimal import Decimal
from contextlib import ExitStack
from pathlib import Path
from typing import TypedDict
from unittest.mock import AsyncMock, patch

import httpx
import redis
from redis.exceptions import ConnectionError as RedisConnectionError
from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

import store_api
from shared_store import RedisSharedStore


class FakeChatState(TypedDict, total=False):
    message: str
    history: list[dict]
    context: dict
    catalog: list[dict]
    products: list[dict]
    sources: list[dict]
    citations: list[str]
    trace: list[dict]
    mode: dict
    answer: str


PUBLIC_PRODUCTS = [
    {"id": "lamp", "code": "DEMO-LAMP-ZB", "name": "演示柔光灯", "category": "演示·智能照明",
     "spec": "Zigbee/9W", "remark": "需兼容网关", "price": 129.0, "stock": 5},
    {"id": "sensor", "code": "DEMO-SENSOR-DOOR", "name": "演示门磁", "category": "演示·智能传感器",
     "spec": "Zigbee/电池", "remark": "演示商品", "price": 69.0, "stock": 8},
    {"id": "sold-out", "code": "DEMO-SWITCH-ZB", "name": "缺货演示开关", "category": "演示·智能安防",
     "spec": "Zigbee/零火", "remark": "演示商品", "price": 79.0, "stock": 0},
    {"id": "unpriced", "code": "DEMO-LOCK-WIFI", "name": "未定价演示门锁", "category": "演示·智能安防",
     "spec": "WiFi/指纹", "remark": "演示商品", "price": None, "stock": 3},
]


class OfflineService:
    ChatState = FakeChatState
    TIMEOUT = httpx.Timeout(1)
    JAVA_URL = "http://java.test"

    def __init__(self):
        self.catalog = copy.deepcopy(PUBLIC_PRODUCTS) + [
            {"id": "private", "code": "ERP-PRIVATE", "name": "内部未上架货品", "category": "内部",
             "spec": "PRIVATE", "remark": "不得给访客", "price": 1.0, "stock": 999}
        ]
        self.answer_delay = 0
        self.received_contexts = []

    async def fetch_data(self, state):
        if state["authorization"] != "Bearer test-reader":
            raise AssertionError("Unexpected business reader credential")
        return {"catalog": copy.deepcopy(self.catalog)}

    async def retrieve(self, state):
        return {"products": [p for p in state["catalog"] if p["id"] == "lamp"],
                "sources": [], "citations": [], "mode": {"llm": "offline-test", "retrieval": "local"}}

    async def answer(self, state):
        self.received_contexts.append(copy.deepcopy(state.get("context", {})))
        if self.answer_delay:
            await asyncio.sleep(self.answer_delay)
        return {"answer": "根据当前演示商品，建议先核对网关兼容性。"}


class FakeCommerceAuthority:
    """The fake Java boundary, separate from SQLite and the catalog reader.

    These checks exercise the C adapter's contract, ownership and failure
    behavior. The real Java transaction/concurrency checks run separately.
    """
    def __init__(self, catalog):
        self.catalog = catalog
        self.orders = {}
        self.request_keys = {}
        self.held = {}
        self.payments = {}
        self.activities = []
        self.down = False

    def stock(self, pid):
        product = next(product for product in self.catalog if product["id"] == pid)
        book = product["stock"]
        reserved = self.held.get(pid, 0)
        return {"productId": pid, "productCode": product["code"], "productName": product["name"],
                "spec": product["spec"], "price": product["price"], "description": product["remark"],
                "categoryName": product["category"], "productStatus": "0", "snapshotReady": True, "listed": pid != "private",
                "bookStock": book, "reservedStock": reserved, "availableStock": book - reserved}

    def response(self, data=None, code=200, msg=""):
        return httpx.Response(200, json={"code": code, "msg": msg, "data": copy.deepcopy(data)})

    def set_status(self, order, status):
        order["status"] = order["orderStatus"] = status
        order["statusName"] = {0: "待支付", 1: "沙箱已支付，待发货", 2: "已发货", 3: "已收货", 4: "已取消"}[status]

    def ship_fixture(self, order_id):
        """Simulate an authority event, not an exposed customer dispatch API."""
        order = self.orders[order_id]
        if order["orderStatus"] != 1:
            raise AssertionError("Only paid fake orders may dispatch")
        for item in order["items"]:
            product = next(product for product in self.catalog if product["id"] == item["productId"])
            product["stock"] -= item["quantity"]
            self.held[item["productId"]] -= item["quantity"]
        self.set_status(order, 2)
        order.update(shippedTime="2026-10-08 10:02:00", receiptId=42,
                     carrier="演示物流", trackingNo="DEMO-TRACK-42")

    def handle(self, request):
        if self.down:
            raise httpx.ConnectError("Java authority unavailable", request=request)
        if request.headers.get("authorization") != "Bearer test-reader":
            raise AssertionError("Commerce authority received an unexpected credential")
        path, method = request.url.path, request.method
        if path == "/commerce/activities" and method == "GET":
            return self.response(self.activities)
        if path == "/commerce/activities/participation" and method == "GET":
            owner = request.url.params.get("ownerId")
            rows = []
            for activity in self.activities:
                joined = [o for o in reversed(list(self.orders.values())) if o["ownerId"] == owner
                          and o.get("activityId") == activity["activityId"] and o["orderStatus"] in (0, 1, 2, 3)]
                rows.append({"activityId": activity["activityId"], "participationCount": len(joined),
                             "myOrderId": joined[0]["orderId"] if joined else None,
                             "myOrderStatus": joined[0]["orderStatus"] if joined else None})
            return self.response(rows)
        if path == "/commerce/catalog" and method == "GET":
            return self.response([{key:value for key,value in self.stock(product["id"]).items() if key not in ('bookStock','reservedStock')}
                                  for product in self.catalog if self.stock(product['id'])['listed']])
        if path == "/commerce/orders" and method == "GET":
            owner = request.url.params.get("ownerId")
            orders = [order for order in reversed(list(self.orders.values())) if order["ownerId"] == owner]
            number, size = int(request.url.params.get("pageNum", 1)), int(request.url.params.get("pageSize", 20))
            return self.response({"rows": orders[(number-1)*size:number*size], "total": len(orders)})
        body = json.loads(request.content) if request.content else {}
        if path == "/commerce/orders" and method == "POST":
            key = (body["ownerId"], body["requestKey"])
            fingerprint = tuple(sorted((str(item["productId"]), item["quantity"]) for item in body["items"]))
            if key in self.request_keys:
                old_id, old_fingerprint = self.request_keys[key]
                return self.response(self.orders[old_id]) if fingerprint == old_fingerprint else self.response(code=409, msg="下单请求内容不同")
            items = []
            for pid, quantity in fingerprint:
                product = next((product for product in self.catalog if product["id"] == pid), None)
                if not product or not self.stock(pid)["listed"]:
                    return self.response(code=404, msg="货品不存在或未上架")
                if product["price"] is None or quantity > self.stock(pid)["availableStock"]:
                    return self.response(code=409, msg="价格或库存不可用")
                unit_price = Decimal(str(product["price"]))
                items.append({"productId": pid, "productCode": product["code"], "productName": product["name"],
                              "spec": product["spec"], "quantity": quantity, "unitPrice": float(unit_price),
                              "amount": float(unit_price * quantity), "cover": store_api.product_shape(product)["cover"]})
            order_id = "SC20261008TEST" + str(len(self.orders)+1).zfill(4)
            order = {"orderId": order_id, "ownerId": body["ownerId"], "orderStatus": 0, "status": 0,
                     "statusName": "待支付", "createTime": "2026-10-08 10:00:00", "items": items,
                     "totalAmount": float(sum(Decimal(str(item["amount"])) for item in items)),
                     "demo": True, "paymentProvider": "LOCAL_SANDBOX", "shippingAddress": copy.deepcopy(body.get("shippingAddress"))}
            self.orders[order_id] = order
            self.request_keys[key] = (order_id, fingerprint)
            for item in items:
                self.held[item["productId"]] = self.held.get(item["productId"], 0) + item["quantity"]
            return self.response(order)
        parts = path.split("/")
        if len(parts) not in (4, 5) or parts[1:3] != ["commerce", "orders"]:
            raise AssertionError(f"Unexpected fake Java request: {method} {path}")
        order = self.orders.get(parts[3])
        owner = request.url.params.get("ownerId") if method == "GET" else body.get("ownerId")
        if not order or order["ownerId"] != owner:
            return self.response(code=404, msg="当前访客订单不存在")
        if method == "GET" and len(parts) == 4:
            return self.response(order)
        action = parts[4] if len(parts) == 5 else ""
        if method == "POST" and action == "cancel":
            if order["orderStatus"] == 4:
                return self.response(order)
            if order["orderStatus"] != 0:
                return self.response(code=409, msg="只有未支付订单可以取消")
            for item in order["items"]:
                self.held[item["productId"]] -= item["quantity"]
            self.set_status(order, 4)
            return self.response(order)
        if method == "POST" and action == "sandbox-pay":
            key = (order["orderId"], body["paymentRequestId"])
            scenario = body["scenario"]
            if key in self.payments:
                if self.payments[key] != scenario:
                    return self.response(code=409, msg="支付请求内容不同")
            elif order["orderStatus"] != 0:
                return self.response(code=409, msg="订单状态不允许支付")
            else:
                self.payments[key] = scenario
                if scenario == "success":
                    self.set_status(order, 1)
                    order.update(paidTime="2026-10-08 10:01:00", transactionId="SANDBOX-"+order["orderId"])
            return self.response({**order, "paymentOutcome": "SUCCESS" if scenario == "success" else "FAILURE"})
        if method == "POST" and action == "receive":
            if order["orderStatus"] not in (2, 3):
                return self.response(code=409, msg="未发货订单不能收货")
            self.set_status(order, 3)
            order["receivedTime"] = "2026-10-08 10:03:00"
            return self.response(order)
        raise AssertionError(f"Unexpected fake Java action: {method} {path}")


class StoreIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.dict("os.environ", {"FUSION_PUBLIC_CACHE_SECONDS": "0"}))
        self.stack.enter_context(patch.dict("os.environ", {"FUSION_COMMERCE_USER": "test-service", "FUSION_COMMERCE_PASSWORD": "fixture-only","FUSION_CUSTOMER_ASSERTION_SECRET":"test-assertion-secret-only-32-characters"}))
        temporary = self.stack.enter_context(tempfile.TemporaryDirectory(prefix="simlect-store-test-"))
        self.stack.enter_context(patch.object(store_api, "DB_PATH", Path(temporary) / "store.sqlite3"))
        self.redis_prefix = "fusion:test:store:" + secrets.token_hex(10)
        self.stack.enter_context(patch.dict(os.environ, {"FUSION_STORE_KEY_PREFIX": self.redis_prefix}))
        self.redis = redis.Redis.from_url(os.getenv("FUSION_REDIS_URL", "redis://127.0.0.1:6379/0"), decode_responses=True)
        self.redis.ping()
        self.addCleanup(StoreIntegrationTests.cleanup_redis, self)
        self.state_keys = RedisSharedStore(store_api.DB_PATH).keys
        self.java_requests = []
        self.service = OfflineService()
        self.authority = FakeCommerceAuthority(self.service.catalog)

        def java_transport(request):
            self.java_requests.append((request.method, request.url.path))
            if request.method == "POST" and request.url.path == "/login":
                return httpx.Response(200, json={"code": 200, "token": "test-reader"})
            return self.authority.handle(request)

        real_async_client = httpx.AsyncClient
        self.stack.enter_context(patch.object(store_api.httpx, "AsyncClient", side_effect=lambda **kwargs:
                                             real_async_client(transport=httpx.MockTransport(java_transport), **kwargs)))
        self.app = FastAPI()
        self.app.include_router(store_api.build_store_router(self.service))
        self.alice = self.stack.enter_context(TestClient(self.app, base_url="http://127.0.0.1:7050"))
        # Independent facade lifecycles and Redis pools, shared server state.
        self.bob_app = FastAPI()
        self.bob_app.include_router(store_api.build_store_router(self.service))
        self.bob = self.stack.enter_context(TestClient(self.bob_app, base_url="http://127.0.0.1:7050"))
        self.login(self.alice)
        self.login(self.bob)

    def cleanup_redis(self):
        keys = list(self.redis.scan_iter(match=self.redis_prefix + ":*"))
        if keys:
            self.redis.delete(*keys)
        self.redis.close()

    def login(self, client):
        response = client.post("/api/account/autoLogin", json={})
        self.assertEqual(response.json()["code"], 200)
        self.assertIn("HttpOnly", response.headers["set-cookie"])
        self.assertIn("SameSite=lax", response.headers["set-cookie"])
        return client.cookies.get(store_api.COOKIE)

    def api(self, client, path, **params):
        response = client.post("/api/" + path, json=params)
        self.assertEqual(response.status_code, 200)
        return response.json()

    def data(self, client, path, **params):
        response = self.api(client, path, **params)
        self.assertEqual(response["code"], 200, response)
        return response["data"]

    def add_cart(self, client, product="lamp", count=1):
        return self.api(client, "productCart/add2Cart", productId=product, buyCount=count, propertyValueIds="default")

    def cart(self, client):
        return self.data(client, "productCart/loadCart")["list"]

    def post_order(self, client, key="request-1", lines=None, **extra):
        return self.api(client, "order/postOrder", payMethod="demo", addressId="demo-address",
                        clientRequestId=key, orderList=lines or [{"productId": "lamp", "buyCount": 1, "propertyValueIds": "default"}], **extra)

    def create_order(self, client, key="request-1", lines=None, **extra):
        result = self.post_order(client, key, lines, **extra)
        self.assertEqual(result["code"], 200, result)
        return result["data"]["orderId"]

    def detail(self, client, order_id):
        return self.data(client, "order/getMyOrderDetail", orderId=order_id)

    def send_question(self, client, question, **extra):
        return self.data(client, "agent/sendMessage", message=question, **extra)["messageId"]

    def await_message(self, client, message_id, expected_status=2):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            rows = self.data(client, "agent/loadHistoryMessage")["list"]
            message = next((row for row in rows if row["messageId"] == message_id), None)
            if message and message["status"] != 1:
                self.assertEqual(message["status"], expected_status, message)
                return message
            time.sleep(0.01)
        self.fail("Offline assistant did not finish within three seconds")

    def proposal(self, client, order_id):
        mid = self.send_question(client, "取消订单 " + order_id)
        payload = json.loads(self.await_message(client, mid)["assistantMessage"])
        self.assertEqual(payload["type"], "ACTION_CONFIRM")
        self.assertEqual(payload["orderId"], order_id)
        return payload["token"]

    def test_sessions_are_distinct_and_missing_or_expired_cookie_is_rejected(self):
        self.assertNotEqual(self.alice.cookies.get(store_api.COOKIE), self.bob.cookies.get(store_api.COOKIE))
        self.bob.cookies.clear()
        self.assertEqual(self.api(self.bob, "account/getUserInfo")["code"], 901)
        token = self.alice.cookies.get(store_api.COOKIE)
        self.redis.delete(self.state_keys(token)[0])
        self.assertEqual(self.api(self.alice, "account/getUserInfo")["code"], 901)

    def test_logout_revokes_old_cookie_and_untrusted_origin_cannot_log_in(self):
        old_cookie = self.alice.cookies.get(store_api.COOKIE)
        self.data(self.alice, "account/logout")
        replay = self.alice.post("/api/account/getUserInfo", json={},
                                 headers={"cookie": f"{store_api.COOKIE}={old_cookie}"})
        self.assertEqual(replay.json()["code"], 901)
        rejected = self.alice.post("/api/account/autoLogin", json={}, headers={"origin": "https://untrusted.test"})
        self.assertEqual(rejected.status_code, 403)
        self.assertEqual(rejected.json()["code"], 403)

    def test_cookie_and_cart_work_across_independent_facade_instances(self):
        session = self.alice.cookies.get(store_api.COOKIE)
        self.bob.cookies.clear()
        self.bob.cookies.set(store_api.COOKIE, session)
        self.assertEqual(self.data(self.bob, "account/getUserInfo")["userId"], session[:16])
        self.add_cart(self.alice, count=1)
        self.assertEqual(self.cart(self.bob)[0]["buyCount"], 1)
        self.add_cart(self.bob, count=1)
        self.assertEqual(self.cart(self.alice)[0]["buyCount"], 2)
        self.data(self.bob, "account/logout")
        self.assertEqual(self.api(self.alice, "account/getUserInfo")["code"], 901)

    def test_redis_outage_returns_http_503_without_creating_local_sessions(self):
        with patch.object(RedisSharedStore, "get_client", new=AsyncMock(side_effect=RedisConnectionError("offline"))):
            for path in ("account/getUserInfo", "account/autoLogin", "productCart/loadCart"):
                response = self.alice.post("/api/" + path, json={})
                self.assertEqual(response.status_code, 503)
                self.assertEqual(response.json()["code"], 503)
                self.assertEqual(response.headers["cache-control"], "private, no-store")
                self.assertNotIn("set-cookie", response.headers)
            with self.assertRaises(WebSocketDisconnect) as raised:
                with self.alice.websocket_connect("ws://127.0.0.1:7050/ws/"):
                    pass
            self.assertEqual(raised.exception.code, 1013)
        with store_api.db() as connection:
            self.assertEqual(connection.execute("SELECT count(*) FROM sessions").fetchone()[0], 0)
        self.assertEqual(self.api(self.alice, "account/getUserInfo")["code"], 200)

    def test_personal_state_and_cookie_responses_are_not_cacheable(self):
        for path in ("account/getUserInfo", "account/autoLogin", "productCart/loadCart", "account/logout"):
            response = self.alice.post("/api/" + path, json={})
            self.assertEqual(response.headers["cache-control"], "private, no-store")
        response = self.alice.post("/api/account/getUserInfo", json={})
        self.assertEqual(response.json()["code"], 901)
        self.assertEqual(response.headers["cache-control"], "private, no-store")

    def test_business_catalog_only_exposes_explicit_demo_products(self):
        products = self.data(self.alice, "product/loadProduct")["list"]
        self.assertEqual({p["productId"] for p in products}, {p["id"] for p in PUBLIC_PRODUCTS})
        self.assertEqual(self.api(self.alice, "product/getProduct", productId="private")["code"], 404)
        self.assertEqual(self.add_cart(self.alice, "private")["code"], 404)
        self.assertTrue(all((method, path) in (("POST", "/login"), ("GET", "/commerce/catalog"))
                            for method, path in self.java_requests))

    def test_campaign_listing_hides_expired_and_private_goods_and_preserves_ordinary_price(self):
        now = datetime.now()
        campaign = {"activityId": "live", "productId": "lamp", "price": 99, "remaining": 2,
                    "capacity": 2, "perOwnerLimit": 1, "startsAt": (now-timedelta(minutes=1)).isoformat(),
                    "endsAt": (now+timedelta(hours=1)).isoformat()}
        self.authority.activities = [campaign, {**campaign, "activityId": "old", "endsAt": (now-timedelta(seconds=1)).isoformat()},
                                     {**campaign, "activityId": "private", "productId": "private"}]
        rows = self.data(self.alice, "seckill/listActivities")
        self.assertEqual([row["activityId"] for row in rows], ["live"])
        self.assertEqual(rows[0]["originalPrice"], 129)
        self.assertEqual(rows[0]["price"], 99)
        self.assertEqual(rows[0]["spec"], "Zigbee/9W")
        self.assertEqual(self.data(self.alice, "product/getProduct", productId="lamp")["productInfo"]["price"], 129)

    def test_campaign_participation_uses_only_current_visitor_active_orders(self):
        now = datetime.now()
        self.authority.activities = [{"activityId": "live", "productId": "lamp", "price": 99, "remaining": 0,
                    "capacity": 1, "perOwnerLimit": 1, "startsAt": (now-timedelta(minutes=1)).isoformat(),
                    "endsAt": (now+timedelta(hours=1)).isoformat()}]
        order_id = self.create_order(self.alice)
        self.authority.orders[order_id]["activityId"] = "live"
        alice = self.data(self.alice, "seckill/myParticipation")[0]
        bob = self.data(self.bob, "seckill/myParticipation")[0]
        self.assertEqual((alice["participationCount"], alice["myOrderId"], alice["myOrderStatus"]), (1, order_id, 0))
        self.assertEqual((bob["participationCount"], bob["myOrderId"]), (0, None))
        self.data(self.alice, "order/cancelOrder", orderId=order_id)
        self.assertEqual(self.data(self.alice, "seckill/myParticipation")[0]["participationCount"], 0)

    def test_public_campaigns_are_anonymous_and_never_read_personal_orders(self):
        self.bob.cookies.clear()
        self.java_requests.clear()
        response = self.bob.get("/api/seckill/listActivities", params={"ownerId": "forged", "shopId": "other"})
        self.assertEqual(response.json()["code"], 200)
        self.assertEqual(response.headers["cache-control"], "public, max-age=2")
        self.assertNotIn("set-cookie", response.headers)
        self.assertNotIn(("GET", "/commerce/orders"), self.java_requests)
        self.assertNotIn(("GET", "/commerce/activities/participation"), self.java_requests)
        self.assertEqual(self.api(self.bob, "seckill/myParticipation")["code"], 901)

    def test_cart_isolated_and_foreign_cart_id_cannot_delete_owner_entry(self):
        self.assertEqual(self.add_cart(self.alice, count=2)["code"], 200)
        self.assertEqual(self.cart(self.bob), [])
        alice_cart_id = self.cart(self.alice)[0]["cartId"]
        self.data(self.bob, "productCart/deleteCart", cartId=alice_cart_id)
        self.assertEqual(self.cart(self.alice)[0]["buyCount"], 2)

    def test_add_to_cart_checks_accumulated_quantity_against_available_stock(self):
        self.assertEqual(self.add_cart(self.alice, count=3)["code"], 200)
        self.assertEqual(self.add_cart(self.alice, count=2)["code"], 200)
        self.assertEqual(self.add_cart(self.alice, count=1)["code"], 409)
        self.assertEqual(self.cart(self.alice)[0]["buyCount"], 5)
        self.assertEqual(self.add_cart(self.alice, count=-4)["code"], 200)
        self.assertEqual(self.cart(self.alice)[0]["buyCount"], 1)
        self.assertEqual(self.add_cart(self.alice, count=-1)["code"], 409)

    def test_zero_fractional_boolean_and_malformed_cart_quantities_are_rejected(self):
        for count in (0, 1.5, True, "not-a-number"):
            with self.subTest(count=count):
                self.assertEqual(self.add_cart(self.alice, count=count)["code"], 422)
        self.assertEqual(self.cart(self.alice), [])

    def test_sold_out_and_unknown_sku_cannot_be_added(self):
        self.assertEqual(self.add_cart(self.alice, "sold-out")["code"], 409)
        self.assertEqual(self.api(self.alice, "productCart/add2Cart", productId="lamp", buyCount=1,
                                  propertyValueIds="made-up-sku")["code"], 422)
        self.assertEqual(self.cart(self.alice), [])

    def test_order_recomputes_prices_reserves_available_stock_and_keeps_book_stock(self):
        original_catalog = copy.deepcopy(self.service.catalog)
        lines = [{"productId": "lamp", "buyCount": 2, "price": 0.01, "itemAmount": 0.01},
                 {"productId": "sensor", "buyCount": 1, "price": 99999}]
        order_id = self.create_order(self.alice, lines=lines, amount=0.01)
        detail = self.detail(self.alice, order_id)
        self.assertEqual(detail["amount"], 327.0)
        self.assertEqual([item["itemAmount"] for item in detail["orderItemList"]], [129.0, 69.0])
        self.assertEqual(self.service.catalog, original_catalog)
        stock = self.authority.stock("lamp")
        self.assertEqual({k: stock[k] for k in ("bookStock", "reservedStock", "availableStock")},
                         {"bookStock": 5, "reservedStock": 2, "availableStock": 3})
        self.assertEqual(self.data(self.alice, "product/getProduct", productId="lamp")["skuList"][0]["stock"], 3)
        self.assertEqual(detail["orderStatus"], 0)
        self.assertTrue(detail["demo"])

    def test_authority_failure_never_creates_a_disconnected_sqlite_order(self):
        self.add_cart(self.alice, count=2)
        self.authority.down = True
        result = self.post_order(self.alice, orderFrom="0")
        self.assertEqual(result["code"], 502, result)
        self.assertEqual(self.authority.orders, {})
        with store_api.db() as connection:
            self.assertEqual(connection.execute("SELECT count(*) FROM orders").fetchone()[0], 0)
        carts = self.redis.hvals(self.state_keys(self.alice.cookies.get(store_api.COOKIE))[2])
        self.assertEqual(json.loads(carts[0])["quantity"], 2)

    def test_direct_and_repeated_cancellation_releases_reservation_once(self):
        order_id = self.create_order(self.alice, lines=[{"productId": "lamp", "buyCount": 2}])
        self.assertEqual(self.authority.stock("lamp")["availableStock"], 3)
        for _ in range(2):
            canceled = self.data(self.alice, "order/cancelOrder", orderId=order_id)
            self.assertEqual(canceled["orderStatus"], 4)
            self.assertEqual(self.authority.stock("lamp")["bookStock"], 5)
            self.assertEqual(self.authority.stock("lamp")["reservedStock"], 0)
            self.assertEqual(self.authority.stock("lamp")["availableStock"], 5)

    def test_failed_sandbox_payment_keeps_pending_status_and_reservation(self):
        order_id = self.create_order(self.alice, lines=[{"productId": "lamp", "buyCount": 2}])
        before = self.authority.stock("lamp")
        for _ in range(2):
            result = self.data(self.alice, "order/sandboxPay", orderId=order_id,
                               paymentRequestId="failed-attempt", scenario="failure")
            self.assertEqual(result["orderStatus"], 0)
            self.assertEqual(result["paymentOutcome"], "FAILURE")
            self.assertEqual(self.authority.stock("lamp"), before)
        self.assertEqual(self.detail(self.alice, order_id)["orderStatus"], 0)

    def test_successful_payment_is_owner_only_idempotent_and_prevents_cancellation(self):
        order_id = self.create_order(self.alice)
        before = self.authority.stock("lamp")
        self.assertEqual(self.api(self.bob, "order/sandboxPay", orderId=order_id,
                                  paymentRequestId="foreign-payment", scenario="success")["code"], 404)
        for _ in range(2):
            paid = self.data(self.alice, "order/sandboxPay", orderId=order_id,
                             paymentRequestId="successful-attempt", scenario="success")
            self.assertEqual(paid["orderStatus"], 1)
            self.assertEqual(paid["paymentProvider"], "LOCAL_SANDBOX")
            self.assertEqual(paid["paymentOutcome"], "SUCCESS")
            self.assertTrue(paid["transactionId"].startswith("SANDBOX-"))
            self.assertEqual(self.authority.stock("lamp"), before)
        self.assertEqual(self.api(self.alice, "order/cancelOrder", orderId=order_id)["code"], 409)
        self.assertEqual(self.detail(self.alice, order_id)["orderStatus"], 1)
        self.assertEqual(self.authority.stock("lamp")["reservedStock"], 1)

    def test_assistant_context_reads_authority_paid_and_shipped_status(self):
        order_id = self.create_order(self.alice)
        self.data(self.alice, "order/sandboxPay", orderId=order_id, paymentRequestId="payment", scenario="success")
        self.await_message(self.alice, self.send_question(self.alice, "我的订单付款状态是什么"))
        context_order = self.service.received_contexts[-1]["orders"][0]
        self.assertEqual(context_order["id"], order_id)
        self.assertEqual(context_order["status"], "PAID")
        self.authority.ship_fixture(order_id)
        self.await_message(self.alice, self.send_question(self.alice, "我的订单发货了吗"))
        context_order = self.service.received_contexts[-1]["orders"][0]
        self.assertEqual(context_order["id"], order_id)
        self.assertEqual(context_order["status"], "SHIPPED")
        self.assertEqual(self.detail(self.alice, order_id)["receiptId"], 42)

    def test_order_aggregates_repeated_product_lines_for_stock_check(self):
        result = self.post_order(self.alice, lines=[{"productId": "lamp", "buyCount": 3},
                                                    {"productId": "lamp", "buyCount": 3}])
        self.assertEqual(result["code"], 409)
        self.assertEqual(self.data(self.alice, "order/loadMyOrder")["totalCount"], 0)

    def test_failed_checkout_keeps_owner_cart_and_creates_no_partial_order(self):
        self.add_cart(self.alice, count=2)
        result = self.post_order(self.alice, orderFrom="0", lines=[{"productId": "lamp", "buyCount": 2},
                                                                   {"productId": "sold-out", "buyCount": 1}])
        self.assertEqual(result["code"], 409)
        self.assertEqual(self.cart(self.alice)[0]["buyCount"], 2)
        self.assertEqual(self.data(self.alice, "order/loadMyOrder")["totalCount"], 0)

    def test_order_rejects_fractional_boolean_quantities_and_unpriced_goods(self):
        for count in (1.5, True):
            with self.subTest(count=count):
                self.assertEqual(self.post_order(self.alice, key=str(count), lines=[{"productId": "lamp", "buyCount": count}])["code"], 422)
        self.assertEqual(self.post_order(self.alice, lines=[{"productId": "unpriced", "buyCount": 1}])["code"], 409)

    def test_payment_and_address_are_restricted_to_demo_contract(self):
        self.assertEqual(self.api(self.alice, "order/postOrder", payMethod="alipay", addressId="demo-address",
                                  orderList=[{"productId": "lamp", "buyCount": 1}])["code"], 422)
        self.assertEqual(self.api(self.alice, "order/postOrder", payMethod="demo", addressId="foreign-address",
                                  orderList=[{"productId": "lamp", "buyCount": 1}])["code"], 422)

    def test_order_idempotency_is_scoped_to_session_and_clears_only_owner_cart(self):
        self.add_cart(self.alice)
        self.add_cart(self.bob)
        first = self.create_order(self.alice, orderFrom="0")
        retry = self.create_order(self.alice, orderFrom="0")
        self.assertEqual(first, retry)
        self.assertEqual(self.data(self.alice, "order/loadMyOrder")["totalCount"], 1)
        self.assertEqual(self.cart(self.alice), [])
        self.assertEqual(len(self.cart(self.bob)), 1)
        other = self.create_order(self.bob)
        self.assertNotEqual(first, other)

    def test_successful_idempotent_retry_survives_later_stock_change(self):
        first = self.create_order(self.alice)
        next(p for p in self.service.catalog if p["id"] == "lamp")["stock"] = 0
        retry = self.post_order(self.alice)
        self.assertEqual(retry["code"], 200, retry)
        self.assertEqual(retry["data"]["orderId"], first)
        self.assertEqual(self.detail(self.alice, first)["amount"], 129.0)

    def test_idempotency_key_with_different_order_lines_is_rejected(self):
        first = self.create_order(self.alice)
        response = self.post_order(self.alice, lines=[{"productId": "lamp", "buyCount": 2}])
        self.assertEqual(response["code"], 409)
        self.assertEqual(self.detail(self.alice, first)["orderItemList"][0]["buyCount"], 1)
        self.assertEqual(self.data(self.alice, "order/loadMyOrder")["totalCount"], 1)

    def test_order_reads_and_direct_cancellation_are_owner_only(self):
        order_id = self.create_order(self.alice)
        self.assertEqual(self.api(self.bob, "order/getMyOrderDetail", orderId=order_id)["code"], 404)
        self.assertEqual(self.api(self.bob, "order/cancelOrder", orderId=order_id)["code"], 404)
        self.assertEqual(self.data(self.bob, "order/loadMyOrder")["list"], [])
        self.assertEqual(self.detail(self.alice, order_id)["orderStatus"], 0)

    def test_cancel_proposal_needs_owner_confirmation_and_is_idempotent(self):
        order_id = self.create_order(self.alice)
        token = self.proposal(self.alice, order_id)
        self.assertEqual(self.detail(self.alice, order_id)["orderStatus"], 0)
        self.assertEqual(self.api(self.bob, "agent/confirmAction", actionToken=token)["code"], 404)
        self.assertEqual(self.detail(self.alice, order_id)["orderStatus"], 0)
        for _ in range(2):
            self.assertTrue(self.data(self.alice, "agent/confirmAction", actionToken=token)["success"])
        self.assertEqual(self.detail(self.alice, order_id)["orderStatus"], 4)
        self.assertEqual(self.api(self.alice, "agent/cancelAction", actionToken=token)["code"], 409)

    def test_declining_or_expiring_proposal_keeps_order_pending(self):
        order_id = self.create_order(self.alice)
        token = self.proposal(self.alice, order_id)
        self.assertFalse(self.data(self.alice, "agent/cancelAction", actionToken=token)["success"])
        self.assertEqual(self.api(self.alice, "agent/confirmAction", actionToken=token)["code"], 409)
        self.assertEqual(self.detail(self.alice, order_id)["orderStatus"], 0)
        expired = self.proposal(self.alice, order_id)
        with store_api.db() as connection:
            connection.execute("UPDATE proposals SET expires=? WHERE token=?", (time.time() - 1, expired))
        self.assertEqual(self.api(self.alice, "agent/confirmAction", actionToken=expired)["code"], 409)
        self.assertEqual(self.detail(self.alice, order_id)["orderStatus"], 0)

    def test_foreign_order_in_question_does_not_cancel_visitors_own_order(self):
        mine = self.create_order(self.alice)
        foreign = self.create_order(self.bob)
        mid = self.send_question(self.alice, "取消订单 " + foreign)
        message = self.await_message(self.alice, mid)
        self.assertEqual(message["bizType"], "chat")
        self.assertNotIn("ACTION_CONFIRM", message["assistantMessage"])
        self.assertEqual(self.detail(self.alice, mine)["orderStatus"], 0)

    def test_chat_history_product_context_and_stop_are_isolated(self):
        self.service.answer_delay = 0.15
        mid = self.send_question(self.alice, "这个灯需要网关吗", consultProductId="lamp")
        self.data(self.bob, "agent/cancelMessage", messageId=mid)
        self.await_message(self.alice, mid)
        self.assertEqual(self.data(self.bob, "agent/loadHistoryMessage")["list"], [])
        self.assertIsNone(self.data(self.bob, "agent/getProductConsultContext"))
        self.assertEqual(self.data(self.alice, "agent/getProductConsultContext")["productId"], "lamp")
        own_mid = self.send_question(self.alice, "继续解释")
        self.assertEqual(self.api(self.alice, "agent/sendMessage", message="另一个问题")["code"], 409)
        self.data(self.alice, "agent/cancelMessage", messageId=own_mid)
        self.await_message(self.alice, own_mid, expected_status=3)
        self.data(self.alice, "agent/clearProductConsult")
        self.assertIsNone(self.data(self.alice, "agent/getProductConsultContext"))

    def test_customer_graph_gets_only_current_visitors_order_context(self):
        mine = self.create_order(self.alice)
        foreign = self.create_order(self.bob)
        self.await_message(self.alice, self.send_question(self.alice, "我的订单状态是什么"))
        context_ids = {order["id"] for order in self.service.received_contexts[-1]["orders"]}
        self.assertEqual(context_ids, {mine})
        self.assertNotIn(foreign, context_ids)

    def test_websocket_authentication_origin_and_heartbeat(self):
        with self.alice.websocket_connect("ws://127.0.0.1:7050/ws/", headers={"origin": "http://127.0.0.1:6001"}) as socket:
            socket.send_text("ping")
            self.assertEqual(socket.receive_text(), "pong")
        for headers in ({"origin": "https://untrusted.test"},):
            with self.assertRaises(WebSocketDisconnect) as caught:
                with self.alice.websocket_connect("ws://127.0.0.1:7050/ws/", headers=headers):
                    pass
            self.assertEqual(caught.exception.code, 1008)
        self.bob.cookies.clear()
        with self.assertRaises(WebSocketDisconnect) as caught:
            with self.bob.websocket_connect("ws://127.0.0.1:7050/ws/"):
                pass
        self.assertEqual(caught.exception.code, 1008)
        session = self.alice.cookies.get(store_api.COOKIE)
        self.redis.delete(self.state_keys(session)[0])
        with self.assertRaises(WebSocketDisconnect) as caught:
            with self.alice.websocket_connect("ws://127.0.0.1:7050/ws/"):
                pass
        self.assertEqual(caught.exception.code, 1008)

    def test_websocket_answers_are_never_broadcast_to_other_sessions(self):
        with self.alice.websocket_connect("ws://127.0.0.1:7050/ws/") as socket:
            bob_mid = self.send_question(self.bob, "推荐智能灯")
            self.await_message(self.bob, bob_mid)
            socket.send_text("ping")
            self.assertEqual(socket.receive_text(), "pong")
            alice_mid = self.send_question(self.alice, "推荐智能灯")
            event = socket.receive_json()
            self.assertEqual(event["messageId"], alice_mid)
            self.assertEqual(event["messageType"], "agent")
            self.assertEqual(json.loads(event["assistantMessage"])["type"], "PRODUCT_SEARCH_RESULT")


if __name__ == "__main__":
    unittest.main(verbosity=2)
