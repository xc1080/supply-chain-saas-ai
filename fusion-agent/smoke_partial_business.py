"""Live partial fulfilment acceptance. No direct SQL or balance resets."""
from contextlib import ExitStack
from decimal import Decimal
import json
from pathlib import Path
import uuid
from smoke_business_lifecycle import merchant, visitor, api, call, balances


def run():
    rid = uuid.uuid4().hex[:12]
    result = {"runId": rid, "paymentProvider": "LOCAL_SANDBOX", "logistics": "SIMULATED"}
    with ExitStack() as stack:
        admin, studio = merchant(stack, "admin"), merchant(stack, "studio_admin")
        buyer, foreign = visitor(stack), visitor(stack)
        rows = api(admin, "GET", "/commerce/inventory")
        products = {p["productCode"]: p for p in rows}
        selected_codes = ["LAB-TAPO-H200", "LAB-TAPO-T100", "LAB-TAPO-L530E"]
        before = balances([products[code] for code in selected_codes])
        other_before = balances(api(studio, "GET", "/commerce/inventory"))
        hub, sensor, lamp = [products[code]["productId"] for code in selected_codes]
        address = call(buyer, "userAddress/addAddress", addressee="分批履约样例", phone="00000000000",
                       address="虚构地址：供应链练习仓一号", defaultType=1)["addressId"]
        scarce = products["LAB-TAPO-H100"]
        call(buyer, "order/postOrder", expected=409, payMethod="demo", addressId=address,
             orderList=[{"productId": str(scarce["productId"]), "buyCount": scarce["availableStock"] + 1}],
             clientRequestId="shortage_" + rid)
        result["shortageRejected"] = True
        quote = api(admin, "POST", "/commerce/planning/quote", {"items": [
            {"productId": scarce["productId"], "quantity": 1}, {"productId": sensor, "quantity": 2}], "units": 20})
        assert quote["inventoryPromisableUnits"] == min(scarce["availableStock"], products["LAB-TAPO-T100"]["availableStock"] // 2)
        result["bundleQuote"] = {k: quote[k] for k in ("requestedUnits", "promisableUnits", "inventoryPromisableUnits", "deliveryStatus")}
        # Explicit model change: H200 is a separately sourced compatible gateway for T100.
        created = call(buyer, "order/postOrder", payMethod="demo", addressId=address,
            orderList=[{"productId": str(p), "buyCount": q} for p, q in ((hub, 1), (sensor, 3), (lamp, 2))],
            clientRequestId="partial_" + rid)
        oid = created["orderId"]; result["orderId"] = oid
        call(buyer, "order/sandboxPay", orderId=oid)
        ship_body = {"requestKey": "ship1_" + rid, "items": [
            {"productId": hub, "quantity": 1}, {"productId": sensor, "quantity": 2}, {"productId": lamp, "quantity": 1}],
            "carrier": "样例物流", "trackingNo": "LAB-" + rid}
        sent = api(admin, "POST", f"/commerce/orders/{oid}/ship", ship_body)
        assert sent["fulfillmentStatus"] == "PARTIALLY_SHIPPED", sent
        assert len(api(admin, "POST", f"/commerce/orders/{oid}/ship", ship_body)["shipments"]) == 1
        api(admin, "POST", f"/commerce/orders/{oid}/ship", {**ship_body, "items": [{"productId": sensor, "quantity": 3}]}, expected=409)
        call(foreign, "order/getMyOrderDetail", expected=404, orderId=oid)

        cases = []
        def case(kind, items, suffix):
            request = {"orderId": oid, "kind": kind, "items": items, "requestKey": suffix + "_" + rid, "reason": "部分履约与售后验收"}
            data = call(buyer, "afterSales/apply", **request)
            cid = data["afterSalesId"]; cases.append(cid)
            assert call(buyer, "afterSales/apply", **request)["afterSalesId"] == cid
            call(foreign, "afterSales/detail", expected=404, afterSalesId=cid)
            api(studio, "GET", "/commerce/after-sales/" + cid, expected=404)
            base = "/commerce/after-sales/" + cid
            api(admin, "POST", base + "/review", {"requestKey": "review_" + rid, "decision": "APPROVE", "note": "逐行核验完成"})
            if kind == "RETURN_REFUND":
                api(admin, "POST", base + "/sandbox-refund", {"requestKey": "early_" + rid, "scenario": "success"}, expected=409)
                accepted = api(admin, "POST", base + "/accept-return", {"requestKey": "accept_" + rid, "condition": "SELLABLE"})
                assert accepted["status"] == "RETURN_RECEIVED"
            body = {"requestKey": "refund_" + rid, "scenario": "success"}
            refunded = api(admin, "POST", base + "/sandbox-refund", body)
            assert refunded["status"] == "REFUNDED"
            assert api(admin, "POST", base + "/sandbox-refund", body)["refundId"] == refunded["refundId"]
            assert api(admin, "GET", "/commerce/inventory/reconciliation")["healthy"]
            return refunded

        case("UNSHIPPED_REFUND", [{"productId": sensor, "quantity": 1}, {"productId": lamp, "quantity": 1}], "unsent")
        case("RETURN_REFUND", [{"productId": sensor, "quantity": 1}], "return1")
        case("RETURN_REFUND", [{"productId": hub, "quantity": 1}, {"productId": sensor, "quantity": 1}, {"productId": lamp, "quantity": 1}], "return2")
        final = call(buyer, "order/getMyOrderDetail", orderId=oid)
        assert final["fulfillmentStatus"] == "RETURNED", final
        assert Decimal(str(final["totalAmount"])) == Decimal(str(final["refundedAmount"]))
        assert len(final["afterSalesCases"]) == 3
        after = [p for p in api(admin, "GET", "/commerce/inventory") if p["productCode"] in selected_codes]
        assert balances(after) == before, (before, balances(after))
        assert balances(api(studio, "GET", "/commerce/inventory")) == other_before
        result.update(afterSalesIds=cases, totalAmount=final["totalAmount"], refundedAmount=final["refundedAmount"],
                      fulfillmentStatus=final["fulfillmentStatus"], stockRestored=True, tenantBoundary=True)
        report = api(admin, "GET", "/commerce/planning/replenishment")
        line = next(p for p in report["items"] if p["productCode"] == "LAB-AQARA-M3" and p["suggestedQuantity"] > 0)
        draft = api(admin, "POST", "/commerce/planning/drafts", {"requestKey": "draft_" + rid,
                    "items": [{"productId": line["productId"], "quantity": min(5, line["suggestedQuantity"])}]})
        approved = api(admin, "POST", "/commerce/planning/drafts/" + draft["draftId"] + "/review", {
                    "requestKey": "approve_" + rid, "decision": "APPROVE", "note": "样例备货计划，尚未执行采购"})
        assert approved["status"] == "APPROVED" and not approved["stockPosted"]
        result["replenishmentDraft"] = {"draftId": approved["draftId"], "status": approved["status"], "executionStatus": approved["executionStatus"]}
    output = Path(__file__).parent.parent / "logs" / "partial-business-live-result.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))
    return result


if __name__ == "__main__":
    run()
