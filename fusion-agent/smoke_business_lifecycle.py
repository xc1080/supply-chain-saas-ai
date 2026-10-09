"""Live local business proof; keeps audit documents and restores stock via after-sales.

Uses real HTTP/Redis/MySQL paths. Payments and logistics remain local simulations.
Never reset stock or erase orders to make the verification pass.
"""
from contextlib import ExitStack
import json
from pathlib import Path
import uuid

import httpx

JAVA = "http://127.0.0.1:8035"
STORE = "http://127.0.0.1:7050"


def api(client, method, path, body=None, expected=200):
    response = client.request(method, path, json=body)
    data = response.json()
    assert data.get("code", response.status_code) == expected, (path, response.status_code, data)
    return data.get("data")


def merchant(stack, name):
    client = stack.enter_context(httpx.Client(base_url=JAVA, timeout=45, trust_env=False))
    body = client.post("/login", json={"username": name, "password": "admin123"}).json()
    assert body.get("code") == 200, body.get("msg")
    client.headers.update({"Authorization": "Bearer " + body["token"], "X-Shop-ID": "default"})
    return client


def visitor(stack):
    client = stack.enter_context(httpx.Client(base_url=STORE, timeout=45, trust_env=False))
    api(client, "GET", "/api/account/autoLogin")
    return client


def call(client, path, expected=200, **body):
    return api(client, "POST", "/api/" + path, body, expected)


def balances(rows):
    return [{key: value for key, value in row.items() if key != "version"} for row in rows]


def run():
    identity = uuid.uuid4().hex[:12]
    report = {"runId": identity, "paymentProvider": "LOCAL_SANDBOX", "logistics": "SIMULATED"}
    with ExitStack() as stack:
        shop = merchant(stack, "admin")
        other_shop = merchant(stack, "studio_admin")
        buyer, other_buyer = visitor(stack), visitor(stack)
        assert api(shop, "GET", "/commerce/context")["tenantId"] == "demo"
        other_before = balances(api(other_shop, "GET", "/commerce/inventory"))
        before = balances(api(shop, "GET", "/commerce/inventory"))
        target = next(row for row in before if row["productCode"] == "LAB-TAPO-L530E")
        pid = str(target["productId"])

        def stock():
            rows = api(shop, "GET", "/commerce/inventory")
            return next(row for row in rows if str(row["productId"]) == pid)

        def healthy():
            result = api(shop, "GET", "/commerce/inventory/reconciliation")
            assert result["healthy"], result

        products = call(buyer, "product/loadProduct", pageNo=1, pageSize=100)["list"]
        sourced = [row for row in products if row.get("technicalProfile")]
        assert len(sourced) == 12, len(sourced)
        public = json.dumps(sourced, ensure_ascii=False)
        assert not any(secret in public for secret in ("costPrice", "supplierCode", "supplierLeadDays", "openingStock"))
        assert all(row["technicalProfile"]["sources"] and row.get("imageIsIllustration") for row in sourced)
        report["sourcedModelsVisible"] = len(sourced)

        address = {"addressee": "流程验收样例", "phone": "00000000000", "address": "虚构地址：样例市样例路一号", "defaultType": 1}
        aid = call(buyer, "userAddress/addAddress", **address)["addressId"]
        rows = call(buyer, "userAddress/loadDataList")
        assert len([row for row in rows if row.get("defaultType") == 1]) == 1, rows
        assert any(row["addressId"] == aid for row in rows)
        assert not any(row["addressId"] == aid for row in call(other_buyer, "userAddress/loadDataList"))
        call(other_buyer, "userAddress/updateAddress", expected=404, addressId=aid, **address)
        order_args = {"payMethod": "demo", "addressId": aid, "orderList": [{"productId": pid, "buyCount": 2, "propertyValueIds": "default"}], "clientRequestId": "business_return_" + identity}
        call(other_buyer, "order/postOrder", expected=422, **order_args)
        created = call(buyer, "order/postOrder", **order_args)
        oid = created["orderId"]
        assert call(buyer, "order/postOrder", **order_args)["orderId"] == oid
        detail = call(buyer, "order/getMyOrderDetail", orderId=oid)
        original_address = {key: address[key] for key in ("addressee", "phone", "address")}
        assert detail["shippingAddress"] == original_address, detail["shippingAddress"]
        call(buyer, "userAddress/updateAddress", addressId=aid, **{**address, "address": "虚构地址：已编辑的新地址二号"})
        assert call(buyer, "order/getMyOrderDetail", orderId=oid)["shippingAddress"] == original_address
        call(buyer, "order/postOrder", expected=409, **order_args)
        assert stock()["reservedStock"] == target["reservedStock"] + 2
        report["addressOwnershipAndImmutableSnapshot"] = True

        assert call(buyer, "order/sandboxPay", orderId=oid)["orderStatus"] == 1
        sent = api(shop, "POST", "/commerce/orders/" + oid + "/ship", {})
        assert sent["orderStatus"] == 2
        assert stock()["bookStock"] == target["bookStock"] - 2
        assert stock()["reservedStock"] == target["reservedStock"]
        case_args = {"orderId": oid, "reason": "流程验收：整单退货", "requestKey": "apply_" + identity}
        call(other_buyer, "afterSales/apply", expected=404, **case_args)
        case = call(buyer, "afterSales/apply", **case_args)
        case_id = case["afterSalesId"]
        assert call(buyer, "afterSales/apply", **case_args)["afterSalesId"] == case_id
        call(other_buyer, "afterSales/detail", expected=404, afterSalesId=case_id)
        api(other_shop, "GET", "/commerce/after-sales/" + case_id, expected=404)
        call(buyer, "order/receiveOrder", expected=409, orderId=oid)
        base = "/commerce/after-sales/" + case_id
        review = {"requestKey": "review_" + identity, "decision": "APPROVE", "note": "验收样例"}
        assert api(shop, "POST", base + "/review", review)["status"] == "AWAITING_RETURN"
        refund = {"requestKey": "refund_" + identity, "scenario": "success"}
        api(shop, "POST", base + "/sandbox-refund", refund, expected=409)
        api(shop, "POST", base + "/accept-return", {"requestKey": "accept_" + identity, "condition": "DAMAGED"}, expected=409)
        assert stock()["bookStock"] == target["bookStock"] - 2
        acceptance = {"requestKey": "accept_" + identity, "condition": "SELLABLE"}
        accepted = api(shop, "POST", base + "/accept-return", acceptance)
        assert accepted["status"] == "RETURN_RECEIVED"
        assert api(shop, "POST", base + "/accept-return", acceptance)["returnReceiptId"] == accepted["returnReceiptId"]
        assert stock()["bookStock"] == target["bookStock"]
        healthy()
        failed = api(shop, "POST", base + "/sandbox-refund", {"requestKey": "refund_failed_" + identity, "scenario": "failure"})
        assert failed["status"] == "RETURN_RECEIVED" and failed["refundOutcome"] == "FAILED"
        completed = api(shop, "POST", base + "/sandbox-refund", refund)
        assert completed["status"] == "REFUNDED"
        assert api(shop, "POST", base + "/sandbox-refund", refund)["refundId"] == completed["refundId"]
        assert api(shop, "POST", base + "/sandbox-refund", {**refund, "requestKey": "retry_" + identity})["refundId"] == completed["refundId"]
        warehouse = api(shop, "GET", "/commerce/inventory/" + pid + "/warehouse-ledger")
        dispatched = [row for row in warehouse if row["receipt_id"] == sent["receiptId"] and row["operation"] == "DISPATCH"]
        returned = [row for row in warehouse if row["receipt_id"] == accepted["returnReceiptId"] and row["operation"] == "RETURN_ACCEPT"]
        assert sorted((row["warehouse_id"], -row["delta_quantity"]) for row in dispatched) == sorted((row["warehouse_id"], row["delta_quantity"]) for row in returned)
        report["shippedReturn"] = {"orderId": oid, "afterSalesId": case_id, "returnReceiptId": accepted["returnReceiptId"], "status": completed["status"], "originalWarehouseRestoredOnce": True}

        unpaid_args = {**order_args, "clientRequestId": "business_unshipped_" + identity, "orderList": [{"productId": pid, "buyCount": 1}]}
        unshipped = call(buyer, "order/postOrder", **unpaid_args)["orderId"]
        call(buyer, "order/sandboxPay", orderId=unshipped)
        pending = call(buyer, "afterSales/apply", orderId=unshipped, requestKey="apply_unshipped_" + identity, reason="流程验收：未发货整单退款")
        path = "/commerce/after-sales/" + pending["afterSalesId"]
        assert api(shop, "POST", path + "/review", {**review, "requestKey": "review_unshipped_" + identity})["status"] == "APPROVED"
        api(shop, "POST", "/commerce/orders/" + unshipped + "/ship", {}, expected=409)
        failed = api(shop, "POST", path + "/sandbox-refund", {"requestKey": "failed_unshipped_" + identity, "scenario": "failure"})
        assert failed["status"] == "APPROVED" and stock()["reservedStock"] == target["reservedStock"] + 1
        done = api(shop, "POST", path + "/sandbox-refund", {"requestKey": "refund_unshipped_" + identity, "scenario": "success"})
        assert done["status"] == "REFUNDED"
        report["unshippedRefund"] = {"orderId": unshipped, "afterSalesId": pending["afterSalesId"], "status": done["status"], "reservationReleased": True}
        assert balances(api(shop, "GET", "/commerce/inventory")) == before
        assert balances(api(other_shop, "GET", "/commerce/inventory")) == other_before
        healthy()
        report.update(inventoryRestored=True, otherTenantUnchanged=True, reconciliationHealthy=True, retainedAuditHistory=True)
    output = Path(__file__).parent.parent / "logs" / "business-lifecycle-result.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("PASS live catalog/address/order/return/refund/isolation", json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    run()
