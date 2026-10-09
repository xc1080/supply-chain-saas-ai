"""Live localhost commerce checks; only explicit DEMO goods may be ordered.

One unit is dispatched and retained with its audit receipt. All test holds are
cancelled. No external payment provider or physical carrier is contacted.
"""
import os
import secrets
from concurrent.futures import ThreadPoolExecutor

import httpx

JAVA = os.getenv("FUSION_JAVA_URL", "http://127.0.0.1:8035").rstrip("/")
STORE = "http://127.0.0.1:6001"


def run():
    with httpx.Client(base_url=JAVA, timeout=25) as java, httpx.Client(base_url=STORE, timeout=25) as alice, httpx.Client(base_url=STORE, timeout=25) as bob:
        login = java.post("/login", json={"username": os.getenv("FUSION_DEMO_USER", "admin"), "password": os.getenv("FUSION_DEMO_PASSWORD", "admin123")}).json()
        assert login["code"] == 200
        java.headers["Authorization"] = "Bearer " + login["token"]

        def business(method, path, **kwargs):
            result = java.request(method, path, **kwargs).json()
            assert result["code"] == 200, (path, result.get("msg"))
            return result["data"]

        def api(client, path, **params):
            return client.post("/api/" + path, json=params).json()

        def data(client, path, **params):
            result = api(client, path, **params)
            assert result["code"] == 200, (path, result.get("info"))
            return result["data"]

        def inventory():
            return {str(p["productId"]): p for p in business("GET", "/commerce/inventory")}

        def create(client, product, qty=1, key=None):
            return api(client, "order/postOrder", payMethod="demo", addressId="demo-address", orderFrom="1",
                       clientRequestId=key or secrets.token_hex(12), amount=0.01,
                       orderList=[{"productId": product, "buyCount": qty, "propertyValueIds": "default", "price": 0.01}])

        def detail(client, identifier):
            return data(client, "order/getMyOrderDetail", orderId=identifier)

        for client in (alice, bob):
            data(client, "account/autoLogin")
        catalog = data(alice, "product/loadProduct")["list"]
        lamp = next(p for p in catalog if p["productCode"] == "DEMO-LAMP-WIFI")
        pid = lamp["productId"]
        assert lamp["stock"] > 0
        before = inventory()[pid]
        request_key = secrets.token_hex(12)
        created = create(alice, pid, key=request_key)
        assert created["code"] == 200, created
        oid = created["data"]["orderId"]
        assert create(alice, pid, key=request_key)["data"]["orderId"] == oid
        assert create(alice, pid, qty=2, key=request_key)["code"] == 409
        order = detail(alice, oid)
        assert order["amount"] == lamp["price"] and order["orderStatus"] == 0 and not order["legacy"]
        assert api(bob, "order/getMyOrderDetail", orderId=oid)["code"] == 404
        assert api(bob, "order/sandboxPay", orderId=oid)["code"] == 404
        reserved = inventory()[pid]
        assert reserved["bookStock"] == before["bookStock"]
        assert reserved["reservedStock"] == before["reservedStock"] + 1
        assert reserved["availableStock"] == before["availableStock"] - 1
        assert java.post(f"/commerce/orders/{oid}/ship", json={"carrier": "演示物流"}).json()["code"] == 409
        assert api(alice, "order/receiveOrder", orderId=oid)["code"] == 409
        print("PASS C order uses server prices, reserves once, rejects foreign owner and premature fulfillment")

        failure_key = "fail-" + secrets.token_hex(12)
        failed = data(alice, "order/sandboxPay", orderId=oid, paymentRequestId=failure_key, scenario="failure")
        assert failed["orderStatus"] == 0 and failed["paymentOutcome"] == "FAILED"
        assert inventory()[pid] == reserved
        assert api(alice, "order/sandboxPay", orderId=oid, paymentRequestId=failure_key, scenario="success")["code"] == 409
        paid = data(alice, "order/sandboxPay", orderId=oid, paymentRequestId="success-" + oid)
        assert paid["orderStatus"] == 1 and paid["transactionId"].startswith("LOCAL-SANDBOX-")
        assert data(alice, "order/sandboxPay", orderId=oid, paymentRequestId="success-" + oid)["transactionId"] == paid["transactionId"]
        assert api(alice, "order/cancelOrder", orderId=oid)["code"] == 409
        assert inventory()[pid] == reserved
        print("PASS sandbox payment failure, retry identity, paid state and cancellation protection")

        dispatched = business("POST", f"/commerce/orders/{oid}/ship", json={"carrier": "演示物流"})
        with ThreadPoolExecutor(max_workers=3) as pool:
            replays = list(pool.map(lambda _: business("POST", f"/commerce/orders/{oid}/ship", json={"carrier": "演示物流"}), range(3)))
        assert all(item["receiptId"] == dispatched["receiptId"] for item in replays)
        after = inventory()[pid]
        assert after["bookStock"] == before["bookStock"] - 1
        assert after["reservedStock"] == before["reservedStock"]
        assert after["availableStock"] == before["availableStock"] - 1
        rows = java.get("/inventory/inventoryItemInquiry/list", params={"productCode": "DEMO-LAMP-WIFI", "pageSize": 500}).json()["rows"]
        assert sum(int(row["planQuantity"]) for row in rows if str(row["productId"]) == pid) == after["bookStock"]
        product = java.get("/baseDate/product/list", params={"productCode": "DEMO-LAMP-WIFI", "pageSize": 500}).json()["rows"]
        assert int(next(p for p in product if str(p["productId"]) == pid)["inventoryQty"]) == after["bookStock"]
        receipt = business("GET", "/sales/salesReceiptQuery/" + dispatched["receiptId"])
        assert receipt["receiptType"] == 3 and receipt["receiptStatus"] == 2 and receipt["originalReceipt"] == oid
        assert sum(int(item["planQuantity"]) for item in receipt["details"]) == 1
        heads = java.get("/sales/salesReceiptQuery/headQuery", params={"systematicReceipt": dispatched["receiptId"], "pageSize": 500}).json()["rows"]
        assert len(heads) == 1
        assert detail(alice, oid)["orderStatus"] == 2
        assert data(alice, "order/receiveOrder", orderId=oid)["orderStatus"] == 3
        assert data(alice, "order/receiveOrder", orderId=oid)["orderStatus"] == 3
        assert inventory()[pid] == after
        print(f"PASS dispatch decrements both inventory fields once and creates sales receipt {dispatched['receiptId']}")

        # Only reserve all currently available demo units, then cancel the winner.
        scarce = next(p for p in catalog if p["productCode"] == "DEMO-LOCK-WIFI")
        sid = scarce["productId"]
        scarce_before = inventory()[sid]
        assert 0 < scarce_before["availableStock"] <= 99
        with ThreadPoolExecutor(max_workers=2) as pool:
            requests = list(pool.map(lambda client: create(client, sid, qty=scarce_before["availableStock"]), (alice, bob)))
        assert sorted(result["code"] for result in requests) == [200, 409], requests
        winner_client = alice if requests[0]["code"] == 200 else bob
        winner = next(result["data"]["orderId"] for result in requests if result["code"] == 200)
        assert inventory()[sid]["availableStock"] == 0
        for _ in range(2):
            assert data(winner_client, "order/cancelOrder", orderId=winner)["orderStatus"] == 4
        assert inventory()[sid] == scarce_before
        assert api(winner_client, "order/sandboxPay", orderId=winner)["code"] == 409
        print("PASS live MySQL concurrent checkout cannot oversell; cancellation releases holds exactly once")
        fresh = data(alice, "product/getProduct", productId=pid)["productInfo"]
        assert fresh["stock"] == after["availableStock"] and fresh["bookStock"] == after["bookStock"]
        assert create(alice, pid, key=request_key)["data"]["orderId"] == oid
        for route in ("order/shipOrder", "commerce/orders"):
            assert api(alice, route, orderId=oid)["code"] == 501
        assert httpx.get(JAVA + "/commerce/orders", timeout=10).json()["code"] == 401
        for item in catalog:
            assert alice.get(item["cover"]).status_code == 200
        print(f"PASS C catalog refresh, API authorization and images; {lamp['productName']} book {before['bookStock']} -> {after['bookStock']}")
        print(f"Live retained completed order: {oid}")


if __name__ == "__main__":
    run()
