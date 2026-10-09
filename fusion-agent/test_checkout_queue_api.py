"""Offline checks for the C facade's durable checkout contract and identity boundary."""
import copy
import hashlib
import json
import os
import unittest
from unittest.mock import patch

import httpx

import store_api
import test_store_api as fixtures


class QueueAuthority(fixtures.FakeCommerceAuthority):
    def __init__(self, catalog):
        super().__init__(catalog)
        self.jobs = {}
        self.keys = {}
        self.requests = []
        self.reject_code = None

    def handle(self, request):
        self.requests.append(request)
        if request.headers.get("x-shop-id") != "configured-shop":
            raise AssertionError("The facade must supply its configured shop, never the customer's header")
        if self.down:
            raise httpx.ConnectError("temporary outage", request=request)
        path = request.url.path
        body = json.loads(request.content) if request.content else {}
        if path == "/commerce/activities/rush/checkout":
            if self.reject_code:
                return self.response(code=self.reject_code, msg="排队人数较多")
            key = (body["ownerId"], body["requestKey"])
            if key not in self.keys:
                job = hashlib.md5(str(key).encode()).hexdigest()
                self.keys[key] = job
                self.jobs[job] = {"jobId": job, "state": "PENDING", "orderId": None,
                                  "attempts": 0, "shopId": "configured-shop", "ownerId": body["ownerId"]}
            public = copy.deepcopy(self.jobs[self.keys[key]])
            public.pop("ownerId")
            return self.response(public)
        if path.startswith("/commerce/checkouts/"):
            job = self.jobs.get(path.rsplit("/", 1)[-1])
            if job is None or request.url.params["ownerId"] != job["ownerId"]:
                return self.response(code=404, msg="排队记录不存在")
            public = copy.deepcopy(job)
            public.pop("ownerId")
            return self.response(public)
        return super().handle(request)


class CheckoutQueueAPItests(unittest.TestCase):
    login = fixtures.StoreIntegrationTests.login
    api = fixtures.StoreIntegrationTests.api
    data = fixtures.StoreIntegrationTests.data

    def setUp(self):
        with patch.dict(os.environ, {"FUSION_SHOP_ID": "configured-shop"}):
            fixtures.StoreIntegrationTests.setUp(self)
        self.authority = QueueAuthority(self.service.catalog)

    def submit(self, client=None, key="queue-one", **extra):
        return self.api(client or self.alice, "seckill/submitOrder", activityId="rush", requestKey=key, addressId="demo-address", **extra)

    def test_acceptance_is_not_an_order_and_retry_keeps_the_same_receipt(self):
        first = self.submit()["data"]
        self.assertEqual(first["state"], "PENDING")
        self.assertIsNone(first["orderId"])
        self.assertEqual(first["jobId"], self.submit()["data"]["jobId"])
        self.assertFalse(self.authority.orders)
        with store_api.db() as connection:
            self.assertEqual(connection.execute("SELECT count(*) FROM orders").fetchone()[0], 0)

    def test_identity_tenant_and_shop_inputs_are_not_forwarded(self):
        first = self.submit(ownerId="forged-owner", tenantId="other", shopId="attacker-shop", price=0.01)
        self.assertEqual(first["code"], 200)
        sent = self.authority.requests[-1]
        body = json.loads(sent.content)
        self.assertEqual(set(body), {"ownerId", "requestKey", "shippingAddress"})
        self.assertEqual(set(body["shippingAddress"]), {"addressee", "phone", "address"})
        session = self.alice.cookies.get(store_api.COOKIE)
        self.assertEqual(body["ownerId"], hashlib.sha256(session.encode()).hexdigest())
        self.assertEqual(sent.headers["x-shop-id"], "configured-shop")
        forged = self.alice.post("/api/seckill/submitOrder", json={"activityId": "rush", "requestKey": "forged-header", "addressId": "demo-address"},
                                 headers={"X-Shop-ID": "other-shop", "X-Tenant-ID": "studio"})
        self.assertEqual(forged.json()["code"], 200)
        self.assertEqual(self.authority.requests[-1].headers["x-shop-id"], "configured-shop")

    def test_status_is_owner_scoped_for_both_get_and_post(self):
        job = self.submit()["data"]["jobId"]
        self.assertEqual(self.api(self.bob, "seckill/getCheckoutStatus", jobId=job)["code"], 404)
        response = self.alice.get("/api/seckill/getCheckoutStatus", params={"jobId": job, "ownerId": "forged"}).json()
        self.assertEqual(response["data"]["state"], "PENDING")
        self.authority.jobs[job].update(state="SUCCEEDED", orderId="SC-FINAL", attempts=1)
        self.assertEqual(self.data(self.alice, "seckill/getCheckoutStatus", jobId=job)["orderId"], "SC-FINAL")

    def test_business_failure_and_queue_pressure_are_explicit(self):
        self.authority.reject_code = 429
        self.assertEqual(self.submit()["code"], 429)
        self.authority.reject_code = None
        job = self.submit()["data"]["jobId"]
        self.authority.jobs[job].update(state="REJECTED", errorCode=409, errorMessage="活动库存已抢完")
        result = self.data(self.alice, "seckill/getCheckoutStatus", jobId=job)
        self.assertEqual(result["state"], "REJECTED")
        self.assertEqual(result["errorMessage"], "活动库存已抢完")
        self.assertIsNone(result["orderId"])

    def test_invalid_or_missing_identity_never_reaches_checkout(self):
        self.assertEqual(self.api(self.alice, "seckill/submitOrder", activityId="bad/path", requestKey="key")["code"], 422)
        self.assertEqual(self.api(self.alice, "seckill/getCheckoutStatus", jobId="../orders")["code"], 422)
        self.assertEqual(self.alice.get("/api/seckill/submitOrder", params={"activityId": "rush", "requestKey": "get"}).json()["code"], 405)
        self.bob.cookies.clear()
        self.assertEqual(self.submit(self.bob)["code"], 901)
        self.assertEqual(len(self.authority.requests), 0)

    def test_authority_outage_never_creates_disconnected_local_order(self):
        self.authority.down = True
        self.assertEqual(self.submit()["code"], 502)
        self.authority.down = False
        self.assertEqual(self.submit()["data"]["state"], "PENDING")
        self.assertFalse(self.authority.orders)

    def test_unlisted_or_foreign_shop_goods_are_hidden_from_catalog(self):
        original = self.authority.stock
        self.authority.stock = lambda pid: {**original(pid), "listed": 1 if pid == "lamp" else 0}
        rows = self.data(self.alice, "product/loadProduct")["list"]
        self.assertEqual([row["productId"] for row in rows], ["lamp"])
        self.assertEqual(self.api(self.alice, "product/getProduct", productId="sensor")["code"], 404)


if __name__ == "__main__":
    unittest.main()
