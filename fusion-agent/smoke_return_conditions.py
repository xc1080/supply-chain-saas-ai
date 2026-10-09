"""Real HTTP proof: refund completion and sellable return are independent."""
from contextlib import ExitStack
import json
from pathlib import Path
import uuid
from smoke_business_lifecycle import api, call, merchant, visitor


def run():
    rid = uuid.uuid4().hex[:12]
    result = {"runId": rid, "dataKind": "SIMULATED", "paymentProvider": "LOCAL_SANDBOX"}
    with ExitStack() as stack:
        admin, buyer = merchant(stack, "admin"), visitor(stack)
        before = next(p for p in api(admin, "GET", "/commerce/inventory") if p["productCode"] == "LAB-TAPO-L530E")
        pid = before["productId"]
        aid = call(buyer, "userAddress/addAddress", addressee="验收去向样例", phone="00000000000",
                   address="虚构地址：样例验收仓", defaultType=1)["addressId"]
        order = call(buyer, "order/postOrder", payMethod="demo", addressId=aid,
            orderList=[{"productId": str(pid), "buyCount": 2}], clientRequestId="condition_" + rid)
        oid = order["orderId"]
        call(buyer, "order/sandboxPay", orderId=oid)
        api(admin, "POST", "/commerce/orders/" + oid + "/ship", {})
        cases = []
        for condition in ("QUALITY_HOLD", "DAMAGED"):
            case = call(buyer, "afterSales/apply", orderId=oid, kind="RETURN_REFUND",
                        requestKey=condition + "_" + rid, reason="样例退货验收去向", items=[{"productId": pid, "quantity": 1}])
            cid = case["afterSalesId"]; base = "/commerce/after-sales/" + cid
            api(admin, "POST", base + "/review", {"requestKey": "review_" + rid, "decision": "APPROVE"})
            body = {"requestKey": "accept_" + rid, "condition": condition}
            received = api(admin, "POST", base + "/accept-return", body)
            assert received["returnCondition"] == condition
            assert api(admin, "POST", base + "/accept-return", body)["returnReceiptId"] == received["returnReceiptId"]
            api(admin, "POST", base + "/accept-return", {**body, "condition": "SELLABLE"}, expected=409)
            refunded = api(admin, "POST", base + "/sandbox-refund", {"requestKey": "refund_" + rid, "scenario": "success"})
            assert refunded["status"] == "REFUNDED" and refunded["returnCondition"] == condition
            cases.append({"afterSalesId": cid, "condition": condition, "receiptId": received["returnReceiptId"]})
            assert api(admin, "GET", "/commerce/inventory/reconciliation")["healthy"]
        stock = next(p for p in api(admin, "GET", "/commerce/inventory") if p["productId"] == pid)
        assert stock["bookStock"] == before["bookStock"]
        assert stock["availableStock"] == before["availableStock"] - 2
        assert stock["unavailableStock"] == before["unavailableStock"] + 2
        result.update(orderId=oid, cases=cases, physicalRestored=True, sellableDeltaAfterRefund=-2)
        final = call(buyer, "order/getMyOrderDetail", orderId=oid)
        assert final["totalAmount"] == final["refundedAmount"]
        for case in cases:
            receipt = api(admin, "GET", "/commerce/after-sales/" + case["afterSalesId"])["returnReceiptId"]
            ledger = api(admin, "GET", f"/commerce/inventory/{pid}/warehouse-ledger")
            warehouse = next(row["warehouse_id"] for row in ledger if row["receipt_id"] == receipt)
            api(admin, "POST", "/commerce/planning/conditions", {"requestKey": "release_" + case["afterSalesId"],
                "productId": pid, "warehouseId": warehouse, "from": case["condition"], "to": "SELLABLE", "quantity": 1,
                "reason": "验收测试结束：虚构样例已完成质检或维修确认，恢复可售"})
        restored = next(p for p in api(admin, "GET", "/commerce/inventory") if p["productId"] == pid)
        for field in ("bookStock", "reservedStock", "availableStock", "unavailableStock"):
            assert restored[field] == before[field], (field, restored, before)
        result["sampleInspectionReleased"] = True
        result["reconciliationHealthy"] = api(admin, "GET", "/commerce/inventory/reconciliation")["healthy"]
    out = Path(__file__).parent.parent / "logs" / "return-conditions-live-result.json"
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__": run()
