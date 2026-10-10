"""Owned fictional PO: split receipts -> source returns -> reverse/reapprove.

Business APIs only. Never touches existing SKU balances or calls a supplier.
The owned SKU is unlisted and all source/audit documents are retained.
"""
from contextlib import ExitStack
from datetime import date, datetime, timezone
from decimal import Decimal
import copy
import json
import os
from pathlib import Path
from urllib.parse import urlparse

from smoke_supply_execution import Acceptance, JAVA, PURCHASE, SafeFailure

ORDER = "/purchase/purchaseOrderProcessing"


class Procurement(Acceptance):
    def __init__(self):
        super().__init__()
        self.report.update({"kind": "OWNED_PURCHASE_SOURCE_CLOSURE", "existingSkuChanged": False,
                            "supplierCalled": False, "paymentUsed": False})

    def run(self):
        if urlparse(JAVA).hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise SafeFailure("Procurement acceptance is restricted to local services")
        with ExitStack() as stack:
            admin = self.merchant(stack, os.getenv("FUSION_SMOKE_USER", "admin"))
            foreign = self.merchant(stack, "studio_admin")
            categories = self.api(admin, "categories", "GET", "/baseDate/product/productTypeTree?status=0")
            def leaves(rows):
                for row in rows:
                    if row.get("children"):
                        yield from leaves(row["children"])
                    elif int(row.get("id", 0)) > 0:
                        yield int(row["id"])
            category = next(leaves(categories))
            suppliers = self.request(admin, "suppliers", "GET", "/baseDate/supplier/list?pageSize=500")["rows"]
            supplier = next(row for row in suppliers if str(row.get("status", "0")) == "0")
            warehouses = self.request(admin, "warehouses", "GET", "/baseDate/warehouse/list?pageSize=500")["rows"]
            warehouse = next(row for row in warehouses if int(row.get("warehouseValid", 0)) == 0)
            sid, wid = str(supplier["supplierId"]), str(warehouse["warehouseId"])
            code = "LAB-PO-" + self.run_id.upper()
            product = {"productCode": code, "productName": "采购来源学习灯 " + self.run_id,
                       "productType": str(category), "productSpecifications": "WiFi/学习专用", "measureUnit": "件",
                       "status": "0", "costPrice": "17.25", "univalence": "39.00", "discount": "1",
                       "upperLimit": "100", "lowerLimit": "0", "defaultWarehouse": wid,
                       "notes": "虚构采购来源验收；不代表真实报价"}
            self.request(admin, "productCreate", "POST", "/baseDate/product/add", body=product)
            rows = self.request(admin, "ownedProduct", "GET", "/baseDate/product/list?productCode=" + code)["rows"]
            self.check("singleOwnedProduct", len(rows) == 1)
            pid = int(rows[0]["productId"])
            self.report["state"].update({"productId": pid, "warehouseId": wid, "supplierId": sid})
            self.api(admin, "listing", "POST", f"/commerce/products/{pid}/listing", {"listed": True})
            oid = "PO_" + self.run_id
            def detail(quantity, price):
                return {"productId": str(pid), "supplierId": sid, "warehousingId": wid,
                        "planQuantity": str(quantity), "univalence": price, "discount": "0.90",
                        "productSpecifications": "WiFi/学习专用", "measureUnit": "件"}
            order = {"systematicOrderForm": oid, "orderFormType": 1, "orderFormStatus": 1,
                     "supplierIds": sid, "warehousingIds": wid, "userIds": "1",
                     "orderDate": date.today().isoformat(), "deliveryDate": date.today().isoformat(),
                     "orderFormNotes": "虚构采购来源闭环，分批收货与原单退供",
                     "details": [detail(6, "17.25"), detail(4, "18.50")]}
            self.report["state"]["purchaseOrderId"] = oid
            self.request(admin, "orderDraft", "POST", ORDER + "/save", body=order)
            self.check("orderDraftCreatesNoInventory", self.stock(admin, pid, "draftStock")["bookStock"] == 0)
            order = self.api(admin, "loadOrderDraft", "GET", ORDER + "/" + oid)
            self.check("sameSkuLinesRemainDistinct", len(order["details"]) == 2
                       and len({row["purchaseLineId"] for row in order["details"]}) == 2)
            order["orderFormStatus"] = 2
            self.request(admin, "orderApprove", "POST", ORDER + "/save", body=order)
            self.check("orderApprovalCreatesNoInventory", self.stock(admin, pid, "approvedPoStock")["bookStock"] == 0)
            self.api(foreign, "foreignTenantCannotReadPo", "GET", ORDER + "/" + oid + "/progress", expected=404)
            progress = self.api(admin, "progressStart", "GET", ORDER + "/" + oid + "/progress")
            first, second = progress["lines"]
            command = {"requestKey": "receive_" + self.run_id, "items": [{"purchaseLineId": first["purchaseLineId"], "quantity": 4}]}
            drafted = self.api(admin, "firstReceiptDraft", "POST", ORDER + "/" + oid + "/receipts", command)
            replay = self.api(admin, "draftCommandReplay", "POST", ORDER + "/" + oid + "/receipts", command)
            self.check("commandCreatesExactlyOneDraft", drafted == replay)
            conflict = copy.deepcopy(command)
            conflict["items"][0]["quantity"] = 3
            self.api(admin, "commandPayloadConflict", "POST", ORDER + "/" + oid + "/receipts", conflict, 409)
            self.api(admin, "draftCountsAgainstQuota", "POST", ORDER + "/" + oid + "/receipts",
                     {"requestKey": "over_" + self.run_id, "items": [{"purchaseLineId": first["purchaseLineId"], "quantity": 3}]}, 409)
            self.check("receiptDraftCreatesNoInventory", self.stock(admin, pid, "beforeReceiptApproval")["bookStock"] == 0)
            def status(receipt_id, value, label):
                receipt = self.api(admin, label + ".load", "GET", PURCHASE + "/" + receipt_id)
                receipt["receiptStatus"] = value
                self.request(admin, label, "POST", PURCHASE + "/save", body=receipt)
                return receipt
            rid = drafted["receiptId"]
            self.report["state"]["firstReceiptId"] = rid
            status(rid, 2, "firstReceiptApproval")
            status(rid, 2, "receiptApprovalReplay")
            self.api(admin, "commandReplayAfterApproval", "POST", ORDER + "/" + oid + "/receipts", command)
            self.check("firstBatchPostsOnlyFour", self.stock(admin, pid, "afterFirstBatch")["bookStock"] == 4)
            next_batch = {"requestKey": "receive_two_" + self.run_id,
                          "items": [{"purchaseLineId": first["purchaseLineId"], "quantity": 2},
                                    {"purchaseLineId": second["purchaseLineId"], "quantity": 4}]}
            drafted2 = self.api(admin, "secondReceiptDraft", "POST", ORDER + "/" + oid + "/receipts", next_batch)
            self.report["state"]["secondReceiptId"] = drafted2["receiptId"]
            loaded = status(drafted2["receiptId"], 2, "secondReceiptApproval")
            self.check("receiptMapperRetainsBothSameSkuLines", len(loaded["details"]) == 2)
            self.check("twoBatchesPostTen", self.stock(admin, pid, "afterAllReceipt")["bookStock"] == 10)
            self.request(admin, "orderCannotDeleteWithReceipts", "POST", ORDER + "/delete",
                         body=[{"systematicOrderForm": oid}], expected=409)
            unapprove = copy.deepcopy(order)
            unapprove["orderFormStatus"] = 1
            self.request(admin, "orderCannotUnapproveWithReceipts", "POST", ORDER + "/save", body=unapprove, expected=409)
            self.request(admin, "salesCannotRewritePurchase", "POST", "/sales/salesOrderProcessing/save",
                         body={**order, "orderFormType": 2}, expected=409)
            sources = self.api(admin, "originalReceiptSources", "GET", ORDER + "/" + oid + "/receipt-sources")
            source = next(row for row in sources if row["sourceReceiptId"] == rid)
            return_command = {"requestKey": "return_" + self.run_id,
                              "items": [{"sourceReceiptLineId": source["sourceReceiptLineId"], "quantity": 2}]}
            returned = self.api(admin, "returnDraft", "POST", ORDER + "/" + oid + "/returns", return_command)
            tid = returned["receiptId"]
            self.report["state"]["supplierReturnId"] = tid
            self.api(admin, "returnCommandReplay", "POST", ORDER + "/" + oid + "/returns", return_command)
            self.check("returnDraftDoesNotDeduct", self.stock(admin, pid, "returnDraftStock")["bookStock"] == 10)
            status(tid, 2, "returnApproval")
            status(tid, 2, "returnApprovalReplay")
            self.check("supplierReturnDeductsOnce", self.stock(admin, pid, "afterReturn")["bookStock"] == 8)
            original = self.api(admin, "sourceForReverse", "GET", PURCHASE + "/" + rid)
            original["receiptStatus"] = 1
            self.request(admin, "originCannotReverseWithReturn", "POST", PURCHASE + "/save", body=original, expected=409)
            status(tid, 1, "returnUnapprove")
            self.check("returnUnapproveRestoresStock", self.stock(admin, pid, "afterReturnUnapprove")["bookStock"] == 10)
            status(tid, 2, "returnReapprove")
            final = self.api(admin, "finalProgress", "GET", ORDER + "/" + oid + "/progress")
            self.check("returnsDoNotReopenPurchaseQuota", final["receivedQuantity"] == 10
                       and final["returnedQuantity"] == 2 and final["netReceived"] == 8 and final["remainingToReceive"] == 0)
            costs = self.api(admin, "costLedger", "GET", "/commerce/costs/ledger?limit=200")
            owned = [row for row in costs["entries"] if int(row["productId"]) == pid]
            self.check("procurementCostExplainsNetStock", sum(Decimal(str(row["amount"])) for row in owned) == Decimal("128.7"))
            self.api(admin, "unlistOwnedSku", "POST", f"/commerce/products/{pid}/listing", {"listed": False})
            reconciliation = self.api(admin, "reconciliation", "GET", "/commerce/inventory/reconciliation")
            self.check("inventoryReconciliationHealthy", reconciliation["healthy"] is True)
            self.report.update({"success": True, "ownedSkuUnlisted": True, "ownedSkuOnHand": 8,
                                "purchaseQuantity": 10, "receivedQuantity": 10, "returnedQuantity": 2})


if __name__ == "__main__":
    acceptance = Procurement()
    try:
        acceptance.run()
    except Exception as failure:
        acceptance.report["success"] = False
        acceptance.report["failedCheck"] = str(failure)[:160] if isinstance(failure, SafeFailure) else type(failure).__name__
    finally:
        acceptance.report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        target = Path(__file__).resolve().parent.parent / "logs" / "procurement-result.json"
        target.write_text(json.dumps(acceptance.report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"success": acceptance.report.get("success", False), "checks": len(acceptance.report["checks"]),
                          "failedCheck": acceptance.report.get("failedCheck"), "report": str(target)}, ensure_ascii=False))
    raise SystemExit(0 if acceptance.report.get("success") else 1)
