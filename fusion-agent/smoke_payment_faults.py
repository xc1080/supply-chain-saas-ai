"""Live sandbox money boundary. All cleanup uses business refund/cancel APIs; no SQL resets."""
from contextlib import ExitStack
from hashlib import sha256
import json
from pathlib import Path
import uuid
from smoke_business_lifecycle import api, merchant


def run():
    rid = uuid.uuid4().hex[:12]
    owner = sha256(("payment-fault-smoke-" + rid).encode()).hexdigest()
    result = {"runId": rid, "paymentProvider": "LOCAL_SANDBOX", "externalChannel": False}
    with ExitStack() as stack:
        admin = merchant(stack, "admin")
        sku = next(row for row in api(admin, "GET", "/commerce/inventory")
                   if row["productCode"] == "LAB-TAPO-L510E")
        before = {key: sku[key] for key in ("bookStock", "availableStock", "reservedStock")}

        def create(suffix):
            return api(admin, "POST", "/commerce/orders", {
                "ownerId": owner, "requestKey": suffix + "_" + rid,
                "shippingAddress": {"addressee": "支付故障样例", "phone": "00000000000",
                                    "address": "虚构地址：支付验收练习仓"},
                "items": [{"productId": sku["productId"], "quantity": 1}]})["orderId"]

        def pay(order, scenario):
            return api(admin, "POST", f"/commerce/orders/{order}/sandbox-pay", {
                "ownerId": owner, "paymentRequestId": "pay_" + rid, "scenario": scenario})

        order = create("unknown")
        unknown = pay(order, "timeout_after_success")
        operation = unknown["paymentOperation"]["operationId"]
        assert unknown["paymentOutcome"] == "UNKNOWN" and unknown["orderStatus"] == 0, unknown
        assert pay(order, "timeout_after_success")["paymentOperation"]["operationId"] == operation
        api(admin, "POST", f"/commerce/orders/{order}/sandbox-pay", {
            "ownerId": owner, "paymentRequestId": "duplicate_" + rid, "scenario": "success"}, expected=409)
        queried = api(admin, "POST", f"/commerce/payments/operations/{operation}/query")
        assert queried["outcome"] == "SUCCEEDED", queried
        assert api(admin, "POST", f"/commerce/payments/operations/{operation}/query")["outcome"] == "SUCCEEDED"
        assert api(admin, "GET", f"/commerce/orders/{order}")["orderStatus"] == 1
        case = api(admin, "POST", f"/commerce/orders/{order}/after-sales", {
            "ownerId": owner, "requestKey": "case_" + rid, "reason": "支付故障验收后退款"})["afterSalesId"]
        base = "/commerce/after-sales/" + case
        api(admin, "POST", base + "/review", {"requestKey": "approve_" + rid, "decision": "APPROVE"})
        pending = api(admin, "POST", base + "/sandbox-refund", {
            "requestKey": "refund_" + rid, "scenario": "refund_pending"})
        refund = pending["refundOperation"]["operationId"]
        assert pending["refundOutcome"] == "PENDING" and pending["status"] == "APPROVED", pending
        held = next(row for row in api(admin, "GET", "/commerce/inventory") if row["productId"] == sku["productId"])
        assert held["reservedStock"] == before["reservedStock"] + 1
        api(admin, "POST", base + "/sandbox-refund", {
            "requestKey": "duplicate_" + rid, "scenario": "success"}, expected=409)
        api(admin, "POST", f"/commerce/payments/operations/{refund}/sandbox-result", {"status": "FAILED"})
        retry = api(admin, "POST", base + "/sandbox-refund", {
            "requestKey": "retry_" + rid, "scenario": "timeout_after_success"})
        recovery = retry["refundOperation"]["operationId"]
        api(admin, "POST", f"/commerce/payments/operations/{recovery}/query")
        api(admin, "POST", f"/commerce/payments/operations/{recovery}/query")
        assert api(admin, "GET", base)["status"] == "REFUNDED"

        late_order = create("late")
        late_operation = pay(late_order, "delayed_success")["paymentOperation"]["operationId"]
        api(admin, "POST", f"/commerce/orders/{late_order}/cancel", {"ownerId": owner})
        compensated = api(admin, "POST", f"/commerce/payments/operations/{late_operation}/sandbox-result", {
            "status": "SUCCEEDED"})
        assert compensated["outcome"] == "COMPENSATED", compensated
        assert api(admin, "POST", f"/commerce/payments/operations/{late_operation}/query")["outcome"] == "COMPENSATED"
        assert api(admin, "GET", f"/commerce/orders/{late_order}")["orderStatus"] == 4
        after = next(row for row in api(admin, "GET", "/commerce/inventory") if row["productId"] == sku["productId"])
        assert {key: after[key] for key in before} == before, (before, after)
        assert api(admin, "GET", "/commerce/inventory/reconciliation")["healthy"]
        money = api(admin, "GET", "/commerce/payments/reconciliation")
        assert not [row for row in money["differences"] if row["orderId"] in (order, late_order)], money
        result.update(orderId=order, afterSalesId=case, lateOrderId=late_order,
                      timeoutRecovered=True, duplicateChargeRejected=True,
                      pendingRefundHeldStock=True, failedRefundRetried=True,
                      lateChargeCompensated=True, stockRestored=True,
                      reconciliationCoverage=money["coverage"], untrackedLegacyOrders=money["untrackedLegacyOrders"])
    output = Path(__file__).parent.parent / "logs" / "payment-faults-live-result.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))
    return result


if __name__ == "__main__":
    run()
