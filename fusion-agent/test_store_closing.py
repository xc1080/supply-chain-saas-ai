"""C-side closing routes against isolated sessions and a fake Java authority."""

from __future__ import annotations

import copy
import hashlib
import hmac
import json
import os
import unittest
from decimal import Decimal
from unittest.mock import patch

import store_api
import test_store_api as fixtures
from commerce_api import owner_id


class ClosingAuthority(fixtures.FakeCommerceAuthority):
    def __init__(self, catalog):
        super().__init__(catalog)
        self.calls = []
        self.shipments = {}
        self.after_sales = {}
        self.parcels = {}
        self.promotions = [{"promotionId": "PR_TEST", "title": "测试优惠", "discountAmount": 10,
                            "productIds": ["lamp"], "enabled": True}]

    def handle(self, request):
        if self.down:
            return super().handle(request)
        body = json.loads(request.content) if request.content else {}
        path, method = request.url.path, request.method
        self.calls.append({"method": method, "path": path, "params": dict(request.url.params),
                           "body": copy.deepcopy(body), "headers": dict(request.headers)})
        if request.headers.get("authorization") != "Bearer test-reader":
            raise AssertionError("Unexpected closing authority credential")

        if path == "/commerce/pricing/promotions" and method == "GET":
            return self.response(self.promotions)
        if path == "/commerce/pricing/quote" and method == "POST":
            if body.get("promotionId") not in (None, "PR_TEST"):
                return self.response(code=409, msg="优惠未生效或已停用")
            original = Decimal(0)
            for line in body["items"]:
                product = next((row for row in self.catalog if row["id"] == line["productId"]), None)
                if product is None:
                    return self.response(code=404, msg="报价商品不存在")
                original += Decimal(str(product["price"])) * line["quantity"]
            discount = Decimal(10) if body.get("promotionId") else Decimal(0)
            breakdown = {"originalAmount": float(original), "discountAmount": float(discount),
                         "shippingAmount": 5, "payableAmount": float(original - discount + 5)}
            return self.response({"shopId": "default", "promotionId": body.get("promotionId"),
                                  "promotionTitle": "测试优惠" if discount else None,
                                  "amountSnapshotVersion": 1, **breakdown,
                                  "amountBreakdown": breakdown, "items": body["items"]})

        if path == "/commerce/orders" and method == "POST":
            response = super().handle(request)
            result = response.json()
            if result["code"] != 200:
                return response
            order = self.orders[result["data"]["orderId"]]
            original = sum(Decimal(str(item["amount"])) for item in order["items"])
            discount = Decimal(10) if body.get("promotionId") else Decimal(0)
            payable = original - discount + 5
            order.update(totalAmount=float(payable), promotionId=body.get("promotionId"),
                         promotionTitle="测试优惠" if discount else None, amountSnapshotVersion=1,
                         amountBreakdown={"originalAmount": float(original), "discountAmount": float(discount),
                                          "shippingAmount": 5, "payableAmount": float(payable),
                                          "paidAmount": 0, "refundedAmount": 0, "refundableAmount": 0})
            for index, item in enumerate(order["items"]):
                item_discount = discount if index == 0 else Decimal(0)
                shipping = 5 if index == 0 else 0
                item["amountBreakdown"] = {"originalAmount": item["amount"], "discountAmount": float(item_discount),
                                            "shippingAmount": shipping,
                                            "payableAmount": float(Decimal(str(item["amount"])) - item_discount + shipping)}
            return self.response(order)

        parts = path.split("/")
        if len(parts) == 5 and parts[1:3] == ["commerce", "shipments"] and parts[4] == "tracking" and method == "GET":
            shipment = self.shipments.get(parts[3])
            if not shipment or shipment["ownerId"] != request.url.params.get("ownerId"):
                return self.response(code=404, msg="当前访客包裹不存在")
            return self.response({"shipmentId": parts[3], "orderId": shipment["orderId"],
                                  "carrierCode": "DEMO", "trackingNo": "DEMO_TRACK_42",
                                  "source": "SIMULATED", "provider": "LOCAL_SIMULATED",
                                  "observedAt": "2026-10-11T00:00:00", "status": "NO_OBSERVATION", "events": []})
        if len(parts) == 5 and parts[1:3] == ["commerce", "after-sales"] and parts[4] == "return-parcel" and method == "POST":
            case = self.after_sales.get(parts[3])
            if not case or case["ownerId"] != body.get("ownerId"):
                return self.response(code=404, msg="当前访客售后不存在")
            key = (parts[3], body["requestKey"])
            fingerprint = (body["carrierCode"], body["trackingNo"])
            if key in self.parcels and self.parcels[key][0] != fingerprint:
                return self.response(code=409, msg="寄回凭证请求内容不同")
            parcel = {"registered": True, "status": "REGISTERED", "carrierCode": body["carrierCode"],
                      "trackingNo": body["trackingNo"], "registeredAt": "2026-10-11T00:00:00"}
            self.parcels.setdefault(key, (fingerprint, parcel))
            return self.response(self.parcels[key][1])
        return super().handle(request)


class StoreClosingTests(unittest.TestCase):
    # Reuse setup and helpers without inheriting the unrelated old test methods.
    login = fixtures.StoreIntegrationTests.login
    api = fixtures.StoreIntegrationTests.api
    data = fixtures.StoreIntegrationTests.data
    post_order = fixtures.StoreIntegrationTests.post_order
    create_order = fixtures.StoreIntegrationTests.create_order
    detail = fixtures.StoreIntegrationTests.detail

    def setUp(self):
        environment = patch.dict(os.environ, {"FUSION_TENANT": "demo", "FUSION_SHOP_ID": "default",
                                             "FUSION_SEED_DEMO_ADDRESS": "1"})
        environment.start()
        self.addCleanup(environment.stop)
        fixtures.StoreIntegrationTests.setUp(self)
        self.authority = ClosingAuthority(self.service.catalog)
        self.alice_owner = owner_id(self.alice.cookies.get(store_api.COOKIE))
        self.bob_owner = owner_id(self.bob.cookies.get(store_api.COOKIE))

    def call_for(self, path):
        return next(call for call in reversed(self.authority.calls) if call["path"] == path)

    def assert_signed_owner(self, call, owner):
        headers = call["headers"]
        self.assertEqual(headers["x-customer-owner"], owner)
        self.assertEqual(headers["x-shop-id"], "default")
        self.assertRegex(headers["x-customer-timestamp"], r"^\d+$")
        self.assertRegex(headers["x-customer-nonce"], r"^[a-f0-9]{32}$")
        signed = "\n".join(("demo", "default", call["method"], call["path"], owner,
                            headers["x-customer-timestamp"], headers["x-customer-nonce"]))
        proof = hmac.new(os.environ["FUSION_CUSTOMER_ASSERTION_SECRET"].encode(), signed.encode(), hashlib.sha256).hexdigest()
        self.assertEqual(headers["x-customer-signature"], proof)

    def shipment_fixture(self):
        order_id = self.create_order(self.alice)
        self.authority.shipments["SH_TEST"] = {"orderId": order_id, "ownerId": self.alice_owner}
        return order_id

    def return_fixture(self):
        self.authority.after_sales["AS_TEST"] = {"ownerId": self.alice_owner, "status": "AWAITING_RETURN"}
        return {"afterSalesId": "AS_TEST", "carrierCode": "SF", "trackingNo": "SF_TRACK_42", "requestKey": "parcel_1"}

    def test_quote_forwards_only_server_pricing_inputs_and_aggregates_quantities(self):
        response = self.alice.post("/api/pricing/quote", json={
            "ownerId": self.bob_owner, "shopId": "other", "tenantId": "other", "promotionId": "PR_TEST",
            "originalAmount": .01, "discountAmount": 9999, "shippingAmount": 0, "payableAmount": .01,
            "amountBreakdown": {"payableAmount": .01},
            "orderList": [{"productId": "sensor", "buyCount": 1, "price": .01},
                          {"productId": "lamp", "buyCount": 1, "unitPrice": .01},
                          {"productId": "lamp", "buyCount": 1, "discountAmount": 9999}]},
            headers={"X-Shop-ID": "other", "X-Customer-Owner": self.bob_owner})
        self.assertEqual(response.json()["code"], 200)
        call = self.call_for("/commerce/pricing/quote")
        self.assertEqual(call["body"], {"items": [{"productId": "lamp", "quantity": 2},
                                                 {"productId": "sensor", "quantity": 1}], "promotionId": "PR_TEST"})
        self.assert_signed_owner(call, "")
        result = response.json()["data"]
        self.assertEqual(result["payableAmount"], 322)
        self.assertEqual(result["amountBreakdown"], {"originalAmount": 327, "discountAmount": 10,
                                                   "shippingAmount": 5, "payableAmount": 322})
        self.assertEqual(self.authority.orders, {})
        self.assertEqual(self.authority.held, {})
        with store_api.db() as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM orders").fetchone()[0], 0)

    def test_quote_without_selection_omits_promotion_and_preserves_authority_failure(self):
        lines = [{"productId": "lamp", "buyCount": 1}]
        result = self.api(self.alice, "pricing/quote", orderList=lines)
        self.assertEqual(result["code"], 200)
        self.assertNotIn("promotionId", self.call_for("/commerce/pricing/quote")["body"])
        rejected = self.api(self.alice, "pricing/quote", orderList=lines, promotionId="PR_EXPIRED")
        self.assertEqual(rejected["code"], 409)
        self.assertEqual(rejected["info"], "优惠未生效或已停用")
        self.assertEqual(self.authority.orders, {})

    def test_promotions_are_get_only_and_do_not_forward_client_scope(self):
        response = self.alice.get("/api/pricing/promotions", params={"ownerId": self.bob_owner, "shopId": "other"})
        self.assertEqual(response.json()["data"], self.authority.promotions)
        call = self.call_for("/commerce/pricing/promotions")
        self.assertEqual((call["params"], call["body"]), ({}, {}))
        self.assert_signed_owner(call, "")
        count = len(self.authority.calls)
        self.assertEqual(self.api(self.alice, "pricing/promotions")["code"], 405)
        self.assertEqual(len(self.authority.calls), count)

    def test_quote_rejects_invalid_input_before_contacting_authority(self):
        valid = {"orderList": [{"productId": "lamp", "buyCount": 1}]}
        payloads = [{}, {"orderList": []}, {"orderList": "[]"}, {"orderList": [1]},
                    {"orderList": [{"productId": "lamp", "buyCount": 1}] * 31},
                    {"orderList": [{"productId": "", "buyCount": 1}]},
                    {"orderList": [{"productId": "lamp", "buyCount": 50}, {"productId": "lamp", "buyCount": 50}]}]
        payloads += [{"orderList": [{"productId": "lamp", "buyCount": count}]} for count in (0, -1, 100, True, 1.5, "x")]
        payloads += [{**valid, "promotionId": value} for value in (True, 1, {}, "", "bad/id", "x" * 33)]
        for payload in payloads:
            with self.subTest(payload=payload):
                count = len(self.authority.calls)
                self.assertEqual(self.alice.post("/api/pricing/quote", json=payload).json()["code"], 422)
                self.assertEqual(len(self.authority.calls), count)

    def test_order_passes_promotion_with_session_owner_and_drops_client_money(self):
        order_id = self.create_order(self.alice, promotionId="PR_TEST", ownerId=self.bob_owner,
                                     shopId="other", tenantId="other", payableAmount=.01, shippingAmount=0,
                                     discountAmount=9999, totalAmount=.01)
        call = self.call_for("/commerce/orders")
        self.assertEqual(call["body"]["ownerId"], self.alice_owner)
        self.assertEqual(call["body"]["promotionId"], "PR_TEST")
        self.assertEqual(call["body"]["items"], [{"productId": "lamp", "quantity": 1, "propertyValueIds": "default"}])
        self.assertEqual(set(call["body"]), {"ownerId", "requestKey", "shippingAddress", "promotionId", "items"})
        self.assert_signed_owner(call, self.alice_owner)
        self.assertEqual(self.authority.orders[order_id]["totalAmount"], 124)

    def test_order_adapter_keeps_order_and_line_amount_breakdowns(self):
        order_id = self.create_order(self.alice, promotionId="PR_TEST")
        authority_order = self.authority.orders[order_id]
        authority_order["amountBreakdown"].update(paidAmount=124, refundedAmount=24, refundableAmount=100)
        result = self.detail(self.alice, order_id)
        self.assertEqual(result["amount"], 124)
        self.assertEqual(result["originalAmount"], float(authority_order["amountBreakdown"]["originalAmount"]))
        self.assertEqual(result["goodsAmount"], result["originalAmount"])
        self.assertEqual(result["amountSnapshotVersion"], 1)
        self.assertEqual(result["amountBreakdown"], authority_order["amountBreakdown"])
        self.assertEqual(result["orderItemList"][0]["amountBreakdown"], authority_order["items"][0]["amountBreakdown"])
        listed = self.data(self.alice, "order/loadMyOrder")["list"]
        self.assertEqual(listed[0]["amountBreakdown"], result["amountBreakdown"])

    def test_invalid_order_promotion_never_creates_order(self):
        for promotion in (True, 1, [], "", "bad/id", "x" * 33):
            with self.subTest(promotion=promotion):
                count = len(self.authority.calls)
                self.assertEqual(self.post_order(self.alice, promotionId=promotion)["code"], 422)
                self.assertEqual(len(self.authority.calls), count)
                self.assertEqual(self.authority.orders, {})

    def test_tracking_uses_session_owner_and_preserves_simulated_observation(self):
        order_id = self.shipment_fixture()
        response = self.alice.get("/api/order/shipmentTracking", params={"shipmentId": "SH_TEST", "ownerId": self.bob_owner})
        self.assertEqual(response.json()["code"], 200)
        data = response.json()["data"]
        self.assertEqual((data["orderId"], data["source"], data["provider"], data["status"], data["events"]),
                         (order_id, "SIMULATED", "LOCAL_SIMULATED", "NO_OBSERVATION", []))
        call = self.call_for("/commerce/shipments/SH_TEST/tracking")
        self.assertEqual(call["params"], {"ownerId": self.alice_owner})
        self.assert_signed_owner(call, self.alice_owner)
        rejected = self.bob.get("/api/order/shipmentTracking", params={"shipmentId": "SH_TEST", "ownerId": self.alice_owner})
        self.assertEqual(rejected.json()["code"], 404)
        self.assert_signed_owner(self.call_for("/commerce/shipments/SH_TEST/tracking"), self.bob_owner)

    def test_return_parcel_ignores_forged_owner_and_extra_fields(self):
        payload = self.return_fixture()
        result = self.api(self.alice, "afterSales/returnParcel", **payload, ownerId=self.bob_owner,
                          status="RETURN_RECEIVED", refundAmount=9999, condition="SELLABLE", shopId="other")
        self.assertEqual(result["code"], 200)
        self.assertEqual(result["data"]["trackingNo"], payload["trackingNo"])
        call = self.call_for("/commerce/after-sales/AS_TEST/return-parcel")
        self.assertEqual(call["body"], {"ownerId": self.alice_owner, **{key: value for key, value in payload.items() if key != "afterSalesId"}})
        self.assert_signed_owner(call, self.alice_owner)
        previous = copy.deepcopy(self.authority.parcels)
        rejected = self.api(self.bob, "afterSales/returnParcel", **{**payload, "requestKey": "foreign"}, ownerId=self.alice_owner)
        self.assertEqual(rejected["code"], 404)
        self.assertEqual(self.authority.parcels, previous)
        self.assert_signed_owner(self.call_for("/commerce/after-sales/AS_TEST/return-parcel"), self.bob_owner)

    def test_return_parcel_preserves_request_key_and_authority_conflicts(self):
        payload = self.return_fixture()
        first = self.api(self.alice, "afterSales/returnParcel", **payload)
        self.assertEqual(self.api(self.alice, "afterSales/returnParcel", **payload), first)
        self.assertEqual(len(self.authority.parcels), 1)
        changed = self.api(self.alice, "afterSales/returnParcel", **{**payload, "trackingNo": "SF_OTHER"})
        self.assertEqual(changed["code"], 409)
        self.assertEqual(changed["info"], "寄回凭证请求内容不同")
        self.assertEqual(first["data"]["trackingNo"], self.authority.parcels[("AS_TEST", "parcel_1")][1]["trackingNo"])

    def test_tracking_and_return_reject_invalid_identifiers_without_authority_calls(self):
        for identity in ("", "bad/id", "bad id", "x" * 41):
            with self.subTest(shipment=identity):
                count = len(self.authority.calls)
                response = self.alice.get("/api/order/shipmentTracking", params={"shipmentId": identity})
                self.assertEqual(response.json()["code"], 422)
                self.assertEqual(len(self.authority.calls), count)
        valid = self.return_fixture()
        invalids = [("afterSalesId", ""), ("afterSalesId", "bad/id"), ("afterSalesId", "x" * 33),
                    ("carrierCode", ""), ("carrierCode", "顺丰"), ("carrierCode", "x" * 33),
                    ("trackingNo", "bad tracking"), ("trackingNo", "x" * 81),
                    ("requestKey", ""), ("requestKey", "x" * 81), ("requestKey", True)]
        for field, value in invalids:
            with self.subTest(field=field, value=value):
                count = len(self.authority.calls)
                self.assertEqual(self.api(self.alice, "afterSales/returnParcel", **{**valid, field: value})["code"], 422)
                self.assertEqual(len(self.authority.calls), count)

    def test_mutation_and_query_http_methods_are_enforced(self):
        payload = self.return_fixture()
        cases = [("GET", "pricing/quote", {"orderList": [{"productId": "lamp", "buyCount": 1}]}),
                 ("POST", "order/shipmentTracking", {"shipmentId": "SH_TEST"}),
                 ("GET", "afterSales/returnParcel", payload),
                 ("GET", "order/postOrder", {"payMethod": "demo", "addressId": "demo-address"})]
        for method, path, params in cases:
            with self.subTest(method=method, path=path):
                count = len(self.authority.calls)
                response = self.alice.request(method, "/api/" + path, **({"json": params} if method == "POST" else {"params": params}))
                self.assertEqual(response.json()["code"], 405)
                self.assertEqual(len(self.authority.calls), count)

    def test_new_routes_require_a_valid_session_before_authority_access(self):
        self.bob.cookies.clear()
        cases = [("GET", "pricing/promotions", {}),
                 ("POST", "pricing/quote", {"orderList": [{"productId": "lamp", "buyCount": 1}]}),
                 ("GET", "order/shipmentTracking", {"shipmentId": "SH_TEST"}),
                 ("POST", "afterSales/returnParcel", self.return_fixture())]
        for method, path, params in cases:
            with self.subTest(path=path):
                count = len(self.authority.calls)
                response = self.bob.request(method, "/api/" + path, **({"json": params} if method == "POST" else {"params": params}))
                self.assertEqual(response.json()["code"], 901)
                self.assertEqual(len(self.authority.calls), count)

    def test_authority_outage_has_no_quote_or_return_side_effects(self):
        payload = self.return_fixture()
        self.authority.down = True
        self.assertEqual(self.api(self.alice, "pricing/quote", orderList=[{"productId": "lamp", "buyCount": 1}])["code"], 502)
        self.assertEqual(self.api(self.alice, "afterSales/returnParcel", **payload)["code"], 502)
        self.assertEqual(self.authority.parcels, {})
        self.assertEqual(self.authority.orders, {})
        self.assertEqual(self.authority.held, {})


if __name__ == "__main__":
    unittest.main(verbosity=2)
