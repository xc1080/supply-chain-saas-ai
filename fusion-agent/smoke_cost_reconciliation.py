"""One owned fictional SKU: purchase -> sale -> return -> sandbox statement correction.

Only business HTTP APIs are used. No SQL, real payment, supplier call, or changes to
existing SKUs. The final one-unit stock is retained with all vouchers and unlisted.
Imported observations are deliberately separate from the immutable channel book.
"""
from contextlib import ExitStack
from datetime import date, datetime, timezone
from decimal import Decimal
import json
import os
from pathlib import Path
import sys
from urllib.parse import urlparse

from smoke_supply_execution import Acceptance, JAVA, STORE, PURCHASE, SafeFailure


RESULT_PATH = Path(__file__).resolve().parent.parent / "logs" / "cost-reconciliation-result.json"


class CostAcceptance(Acceptance):
    def __init__(self):
        super().__init__()
        self.report.update({"kind": "DOCUMENT_COST_AND_SANDBOX_RECONCILIATION", "quantity": 1,
                            "existingSkuChanged": False, "supplierCalled": False,
                            "costBasis": "DOCUMENT_SNAPSHOT_NOT_FIFO", "retainedFinalStock": 1})

    def run(self):
        for base in (JAVA, STORE):
            if urlparse(base).hostname not in {"127.0.0.1", "localhost", "::1"}:
                raise SafeFailure("Cost verification is limited to local services")
        with ExitStack() as stack:
            admin = self.merchant(stack, os.getenv("FUSION_SMOKE_USER", "admin"))
            reviewer = self.merchant(stack, "demo_supply_reviewer")
            studio = self.merchant(stack, "studio_admin")
            buyer = self.visitor(stack)
            pid = None
            try:
                name = "成本学习仓 " + self.run_id
                self.request(admin, "warehouseCreate", "POST", "/baseDate/warehouse/add", body={
                    "warehouseName": name, "warehouseValid": 0, "warehouseDirector": "学习模拟",
                    "warehouseTelephone": "00000000000", "warehouseAddress": "虚构成本验收仓",
                    "warehouseNotes": "成本验收独立资料 " + self.run_id})
                rows = self.request(admin, "ownedWarehouse", "GET", "/baseDate/warehouse/list?pageSize=500")["rows"]
                warehouse = int(next(row for row in rows if row["warehouseName"] == name)["warehouseId"])
                code = "LAB-COST-" + self.run_id.upper()
                product = {"productCode": code, "productName": "成本学习 WiFi 灯 " + self.run_id,
                           "productSpecifications": "WiFi/成本学习", "measureUnit": "件", "status": "0",
                           "costPrice": "17.25", "univalence": "39.90", "discount": "100", "upperLimit": "100",
                           "lowerLimit": "0", "defaultWarehouse": str(warehouse), "notes": "本地成本与对账学习专用"}
                self.request(admin, "ownedProductCreate", "POST", "/baseDate/product/add", body=product)
                rows = self.request(admin, "ownedProductRead", "GET", "/baseDate/product/list?productCode=" + code)["rows"]
                self.check("singleOwnedProduct", len(rows) == 1 and rows[0]["productCode"] == code)
                pid = int(rows[0]["productId"])
                self.report["state"].update({"productId": pid, "productCode": code, "warehouseId": warehouse})
                self.api(admin, "ownedListing", "POST", f"/commerce/products/{pid}/listing", {"listed": True})
                self.check("initialOwnedStockZero", self.stock(admin, pid, "initialStock")["bookStock"] == 0)
                rid = "COST_PUR_" + self.run_id
                body = {"systematicReceipt": rid, "originalReceipt": "COST-" + self.run_id, "receiptCategory": 1,
                        "receiptType": 1, "receiptStatus": 1, "invoiceDate": date.today().isoformat(),
                        "warehousingIds": str(warehouse), "retrievalIds": "0", "userIds": "1", "supplierIds": "0",
                        "customerIds": "0", "deposit": "0", "totalAmount": "17.25",
                        "receiptNotes": "虚构成本验收采购一件，原始凭证保留",
                        "details": [{"productId": str(pid), "warehousingId": str(warehouse), "retrievalId": "0",
                                     "supplierId": "0", "customerId": "0", "measureUnit": "件",
                                     "productSpecifications": "WiFi/成本学习", "planQuantity": "1",
                                     "univalence": "17.25", "discount": "100", "money": "17.25", "cost": "17.25"}]}
                self.request(admin, "purchaseDraftSave", "POST", PURCHASE + "/save", body=body)
                ledger = self.api(admin, "costBeforeApproval", "GET", "/commerce/costs/ledger?limit=200")["entries"]
                self.check("draftDoesNotPostCost", not any(row["receiptId"] == rid for row in ledger))
                body["receiptStatus"] = 2
                self.request(admin, "purchaseApprove", "POST", PURCHASE + "/save", body=body)
                self.request(admin, "purchaseReplay", "POST", PURCHASE + "/save", body=body)
                self.report["state"]["purchaseReceiptId"] = rid
                self.check("purchasePostsOnePhysicalUnit", self.stock(admin, pid, "afterPurchase")["bookStock"] == 1)
                address = self.call(buyer, "ownedAddress", "userAddress/addAddress", addressee="成本学习访客",
                                    phone="00000000000", address="虚构地址：成本验收", defaultType=1)["addressId"]
                order = self.call(buyer, "customerOrder", "order/postOrder", payMethod="demo", addressId=address,
                                  clientRequestId="cost_order_" + self.run_id,
                                  orderList=[{"productId": str(pid), "buyCount": 1, "propertyValueIds": "default"}])
                oid = order["orderId"]
                self.report["state"]["orderId"] = oid
                self.call(buyer, "sandboxPayment", "order/sandboxPay", orderId=oid,
                          paymentRequestId="cost_pay_" + self.run_id, scenario="success")
                ship_body = {"requestKey": "cost_ship_" + self.run_id, "warehouseId": warehouse,
                             "items": [{"productId": pid, "quantity": 1}], "carrier": "模拟成本物流",
                             "trackingNo": "COST-" + self.run_id}
                sent = self.api(admin, "dispatch", "POST", f"/commerce/orders/{oid}/ship", ship_body)
                self.api(admin, "dispatchReplay", "POST", f"/commerce/orders/{oid}/ship", ship_body)
                self.report["state"]["dispatchReceiptId"] = sent["receiptId"]
                self.request(admin, "ownedMasterCostAndPriceChange", "PUT", "/baseDate/product/update",
                             body={**product, "productId": str(pid), "costPrice": "99.99", "univalence": "49.90"})
                case = self.call(buyer, "customerReturnRequest", "afterSales/apply", orderId=oid,
                                 requestKey="cost_case_" + self.run_id, kind="RETURN_REFUND",
                                 reason="虚构成本验收：验证退货按原出库成本回转", items=[{"productId": str(pid), "quantity": 1}])
                cid = case["afterSalesId"]
                self.report["state"]["afterSalesId"] = cid
                self.api(admin, "returnApprove", "POST", f"/commerce/after-sales/{cid}/review",
                         {"requestKey": "cost_review_" + self.run_id, "decision": "APPROVE", "note": "仅虚构成本验收"})
                accept_body = {"requestKey": "cost_accept_" + self.run_id, "condition": "SELLABLE"}
                returned = self.api(admin, "returnAcceptance", "POST", f"/commerce/after-sales/{cid}/accept-return", accept_body)
                self.api(admin, "returnAcceptanceReplay", "POST", f"/commerce/after-sales/{cid}/accept-return", accept_body)
                self.report["state"]["returnReceiptId"] = returned["returnReceiptId"]
                refunded = self.api(admin, "sandboxRefund", "POST", f"/commerce/after-sales/{cid}/sandbox-refund",
                                    {"requestKey": "cost_refund_" + self.run_id, "scenario": "success"})
                self.check("refundSucceeded", refunded["status"] == "REFUNDED")
                ledger = self.api(admin, "costLedger", "GET", "/commerce/costs/ledger?limit=200")["entries"]
                own = [row for row in ledger if int(row["productId"]) == pid]
                purchases = [row for row in own if row["eventType"] == "PURCHASE"]
                dispatch = [row for row in own if row["eventType"] == "DISPATCH"]
                returns = [row for row in own if row["eventType"] == "RETURN"]
                self.check("oneCostEventPerBusinessMovement", len(purchases) == len(dispatch) == len(returns) == 1)
                self.check("purchaseOriginalCost", Decimal(str(purchases[0]["unitCost"])) == Decimal("17.25"))
                self.check("shipmentHistoricalCost", Decimal(str(dispatch[0]["unitCost"])) == Decimal("17.25"))
                self.check("returnReferencesOriginalCost", returns[0]["originEntryId"] == dispatch[0]["entryId"]
                           and Decimal(str(returns[0]["unitCost"])) == Decimal("17.25")
                           and Decimal(str(returns[0]["amount"])) + Decimal(str(dispatch[0]["amount"])) == 0)
                self.api(reviewer, "supplyReviewerCannotReadPrivateCost", "GET", "/commerce/costs/ledger", expected=403)
                self.api(studio, "foreignTenantCannotReadOrderCost", "GET", f"/commerce/costs/orders/{oid}", expected=404)
                original = self.api(admin, "originalMoneyFacts", "GET", f"/commerce/costs/orders/{oid}")
                self.check("salePriceSnapshotSurvivesMasterChange", Decimal(str(original["businessPaid"])) == Decimal("39.90")
                           and Decimal(str(original["businessRefunded"])) == Decimal("39.90"))
                operations = original["operations"]
                self.check("oneChargeOneRefund", len(operations) == 2 and {row["kind"] for row in operations} == {"PAYMENT", "REFUND"})
                payment = next(row for row in operations if row["kind"] == "PAYMENT")
                self.check("noFalseCompleteBeforeStatement", original["healthy"] is False and original["observationCoverage"] == "PARTIAL")
                for operation in operations:
                    amount = Decimal(str(operation["amount"])) - (Decimal("0.01") if operation["kind"] == "PAYMENT" else Decimal(0))
                    obs_body = {"requestKey": "cost_obs_" + operation["kind"] + "_" + self.run_id,
                                "operationId": operation["operationId"], "status": "SUCCEEDED", "amount": str(amount),
                                "sourceReference": "虚构沙箱对账单-" + self.run_id}
                    observation = self.api(admin, "import" + operation["kind"], "POST", "/commerce/costs/observations", obs_body)
                    replay = self.api(admin, "importReplay" + operation["kind"], "POST", "/commerce/costs/observations", obs_body)
                    self.check("importReplayStable" + operation["kind"], observation["observationId"] == replay["observationId"])
                rec_body = {"requestKey": "cost_reconcile_" + self.run_id, "orderId": oid}
                mismatch = self.api(admin, "saveMismatch", "POST", "/commerce/costs/reconciliations", rec_body)
                recid = mismatch["reconciliationId"]
                self.report["state"]["reconciliationId"] = recid
                self.check("oneCentDifferenceDetected", mismatch["status"] == "OPEN"
                           and any(row["type"] == "STATEMENT_MISMATCH" and Decimal(str(row["difference"])) == Decimal("-0.01")
                                   for row in mismatch["snapshot"]["issues"]))
                resolution_body = {"requestKey": "cost_resolve_" + self.run_id,
                                   "evidenceReference": "虚构沙箱对账更正凭证-" + self.run_id,
                                   "note": "核对原订单、支付与退款操作后重新导入正确观测，原差异记录保留"}
                self.api(admin, "cannotResolveUncorrectedDifference", "POST", f"/commerce/costs/reconciliations/{recid}/resolve",
                         resolution_body, expected=409)
                self.api(admin, "importCorrection", "POST", "/commerce/costs/observations",
                         {"requestKey": "cost_corrected_" + self.run_id, "operationId": payment["operationId"],
                          "status": "SUCCEEDED", "amount": str(payment["amount"]),
                          "sourceReference": resolution_body["evidenceReference"]})
                final_money = self.api(admin, "correctedMoneyFacts", "GET", f"/commerce/costs/orders/{oid}")
                self.check("correctionDoesNotChangeOriginalBooks", all(final_money[key] == original[key] for key in
                           ("businessPaid", "businessRefunded", "channelCharged", "channelRefunded", "expectedNet", "channelNet")))
                self.check("currentMoneyReconciled", final_money["healthy"] is True and final_money["observationCoverage"] == "COMPLETE")
                resolved = self.api(admin, "resolveWithEvidence", "POST", f"/commerce/costs/reconciliations/{recid}/resolve", resolution_body)
                replay = self.api(admin, "resolutionReplay", "POST", f"/commerce/costs/reconciliations/{recid}/resolve", resolution_body)
                self.check("resolutionIdempotentAndOriginalMismatchRetained", resolved["status"] == "RESOLVED"
                           and resolved["snapshot"]["healthy"] is False and resolved["resolution"]["verifiedSnapshot"]["healthy"] is True
                           and replay["resolution"]["resolutionId"] == resolved["resolution"]["resolutionId"])
                final_stock = self.stock(admin, pid, "finalOwnedStock")
                self.check("finalStockExplainedByPurchaseAndReturn", final_stock["bookStock"] == final_stock["availableStock"] == 1
                           and final_stock["reservedStock"] == 0)
                stock_reconciliation = self.api(admin, "stockReconciliation", "GET", "/commerce/inventory/reconciliation")
                self.check("stockStillHealthy", stock_reconciliation["healthy"] is True)
                self.report.update({"status": "PASSED", "finalBookStock": 1, "historicalCostPreserved": True,
                                    "originalBooksUnchangedByObservation": True, "statementDifferenceResolvedWithEvidence": True})
            except Exception as error:
                self.report.update({"status": "FAILED", "errorType": type(error).__name__,
                                    "failedCheck": str(error) if isinstance(error, SafeFailure) else "Diagnostic suppressed to protect credentials",
                                    "retainedBusinessActionRequired": "Resume owned retained IDs through business APIs; do not reset stock or delete audit"})
            finally:
                if pid is not None:
                    try:
                        self.api(admin, "unlistOwnedTestSku", "POST", f"/commerce/products/{pid}/listing", {"listed": False})
                        self.report["ownedSkuUnlisted"] = True
                    except Exception as error:
                        self.report["cleanupErrorType"] = type(error).__name__
        self.report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        self.report["passedChecks"] = sum(row["passed"] for row in self.report["checks"])
        self.report["failedChecks"] = sum(not row["passed"] for row in self.report["checks"])
        RESULT_PATH.parent.mkdir(parents=True, exist_ok=True)
        RESULT_PATH.write_text(json.dumps(self.report, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        summary = {key: value for key, value in self.report.items() if key != "checks"}
        summary["resultPath"] = str(RESULT_PATH)
        print(json.dumps(summary, ensure_ascii=False, separators=(",", ":")))
        return 0 if self.report.get("status") == "PASSED" else 1


if __name__ == "__main__":
    try:
        sys.exit(CostAcceptance().run())
    except Exception as error:
        print(json.dumps({"status": "FAILED", "errorType": type(error).__name__,
                          "diagnostic": str(error) if isinstance(error, SafeFailure) else "Diagnostic suppressed to protect credentials"}))
        sys.exit(1)
