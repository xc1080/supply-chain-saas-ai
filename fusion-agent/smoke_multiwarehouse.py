"""Owned fictional SKU: two warehouses -> C orders -> partial dispatch -> refund/return.

Uses business APIs exclusively, local sandbox payments and simulated logistics.
The final ERP supplier-return document drains only this run's owned SKU. All order,
receipt, warehouse and allocation histories remain, and the SKU is unlisted.
"""
from contextlib import ExitStack
from datetime import date, datetime, timezone
import json
import os
from pathlib import Path
from urllib.parse import urlparse

from smoke_supply_execution import Acceptance, JAVA, STORE, PURCHASE, SafeFailure


class MultiWarehouse(Acceptance):
    def __init__(self):
        super().__init__()
        self.report.update({"kind": "OWNED_TWO_WAREHOUSE_ALLOCATION", "quantity": 10,
                            "existingSkuChanged": False, "supplierCalled": False})

    def receipt(self, admin, pid, quantities, label, receipt_type=1):
        rid = "MW_" + label + "_" + self.run_id
        details = []
        for wid, quantity in quantities.items():
            details.append({"productId": str(pid), "warehousingId": str(wid) if receipt_type == 1 else "0",
                            "retrievalId": str(wid) if receipt_type == 2 else "0", "supplierId": "0",
                            "customerId": "0", "measureUnit": "件", "productSpecifications": "WiFi/学习专用",
                            "planQuantity": str(quantity), "univalence": "12.00", "discount": "100",
                            "money": str(quantity * 12), "cost": "12.00", "remarks": "虚构两仓学习验收"})
        body = {"systematicReceipt": rid, "originalReceipt": "MW-" + self.run_id, "receiptCategory": 1,
                "receiptType": receipt_type, "receiptStatus": 2, "invoiceDate": date.today().isoformat(),
                "warehousingIds": str(next(iter(quantities))) if receipt_type == 1 else "0",
                "retrievalIds": str(next(iter(quantities))) if receipt_type == 2 else "0", "userIds": "1",
                "supplierIds": "0", "customerIds": "0", "deposit": "0", "totalAmount": str(sum(quantities.values()) * 12),
                "receiptNotes": "虚构多仓验收；只操作本次独立商品，保留原始凭证", "details": details}
        self.request(admin, label + ".postedReceipt", "POST", PURCHASE + "/save", body=body)
        self.report["state"][label + "ReceiptId"] = rid
        return rid

    def allocation(self, admin, order, name):
        return self.api(admin, name, "GET", f"/commerce/orders/{order}/warehouse-allocations")

    @staticmethod
    def pending(rows):
        return {int(row["warehouseId"]): int(row["reservedQuantity"]) for row in rows}

    def refund(self, admin, buyer, oid, pid, kind, quantity, name):
        applied = self.call(buyer, name + ".apply", "afterSales/apply", orderId=oid,
                            requestKey="mw_" + name + "_" + self.run_id, kind=kind,
                            reason="虚构两仓学习验收", items=[{"productId": str(pid), "quantity": quantity}])
        aid = applied["afterSalesId"]
        self.report["state"][name + "AfterSalesId"] = aid
        self.api(admin, name + ".review", "POST", f"/commerce/after-sales/{aid}/review",
                 {"requestKey": "review_" + name + "_" + self.run_id, "decision": "APPROVE", "note": "仅虚构验收"})
        if kind == "RETURN_REFUND":
            self.api(admin, name + ".accept", "POST", f"/commerce/after-sales/{aid}/accept-return",
                     {"requestKey": "accept_" + self.run_id, "condition": "SELLABLE"})
        body = {"requestKey": "refund_" + name + "_" + self.run_id, "scenario": "success"}
        final = self.api(admin, name + ".refund", "POST", f"/commerce/after-sales/{aid}/sandbox-refund", body)
        replay = self.api(admin, name + ".refundReplay", "POST", f"/commerce/after-sales/{aid}/sandbox-refund", body)
        self.check(name + ".refundOnce", final["refundedAmount"] == replay["refundedAmount"])

    def run(self):
        for base in (JAVA, STORE):
            if urlparse(base).hostname not in {"127.0.0.1", "localhost", "::1"}:
                raise SafeFailure("Multiwarehouse verification is restricted to local services")
        with ExitStack() as stack:
            admin = self.merchant(stack, os.getenv("FUSION_SMOKE_USER", "admin"))
            other_tenant = self.merchant(stack, "studio_admin")
            buyer = self.visitor(stack)
            names = ["学习多仓 A " + self.run_id, "学习多仓 B " + self.run_id]
            for index, name in enumerate(names):
                self.request(admin, "warehouse" + str(index) + ".create", "POST", "/baseDate/warehouse/add",
                             body={"warehouseName": name, "warehouseValid": 0, "warehouseDirector": "学习模拟",
                                   "warehouseTelephone": "00000000000", "warehouseAddress": "虚构仓库地址",
                                   "warehouseNotes": "多仓验收独立资料 " + self.run_id})
            warehouse_rows = self.request(admin, "ownedWarehouses", "GET", "/baseDate/warehouse/list?pageSize=500")['rows']
            selected = [next(row for row in warehouse_rows if row["warehouseName"] == name) for name in names]
            a, b = sorted(int(row["warehouseId"]) for row in selected)
            self.report["state"].update({"warehouseIds": [a, b]})
            code = "LAB-WARE-" + self.run_id.upper()
            product = {"productCode": code, "productName": "多仓学习 WiFi 灯 " + self.run_id,
                       "productSpecifications": "WiFi/学习专用", "measureUnit": "件", "status": "0",
                       "costPrice": "12.00", "univalence": "39.00", "discount": "100", "upperLimit": "100",
                       "lowerLimit": "0", "defaultWarehouse": str(a), "notes": "仅用于本地多仓流程学习"}
            self.request(admin, "ownedProductCreate", "POST", "/baseDate/product/add", body=product)
            product_rows = self.request(admin, "ownedProductRead", "GET", "/baseDate/product/list?productCode=" + code)['rows']
            self.check("singleOwnedProduct", len(product_rows) == 1 and product_rows[0]["productCode"] == code)
            pid = int(product_rows[0]["productId"])
            self.report["state"].update({"productId": pid, "productCode": code})
            self.receipt(admin, pid, {a: 3, b: 7}, "purchase")
            self.api(admin, "listingOwnSku", "POST", f"/commerce/products/{pid}/listing", {"listed": True})
            self.check("twoWarehousesTenOnHand", self.stock(admin, pid, "initialStock")["bookStock"] == 10)
            address = self.call(buyer, "ownedAddress", "userAddress/addAddress", addressee="多仓学习访客",
                                phone="00000000000", address="虚构地址：多仓学习验收", defaultType=1)["addressId"]
            def order(name, quantity):
                result = self.call(buyer, name + ".order", "order/postOrder", payMethod="demo", addressId=address,
                                   clientRequestId="mw_" + name + "_" + self.run_id,
                                   orderList=[{"productId": str(pid), "buyCount": quantity, "propertyValueIds": "default"}])
                oid = result["orderId"]
                self.report["state"][name + "OrderId"] = oid
                return oid
            first, second = order("first", 5), order("second", 3)
            self.check("firstOrderExactCommitments", self.pending(self.allocation(admin, first, "firstAllocation")) == {a: 3, b: 2})
            self.check("secondOrderCannotClaimFirstWarehouse", self.pending(self.allocation(admin, second, "secondAllocation")) == {b: 3})
            self.api(other_tenant, "foreignTenantCannotReadAllocation", "GET", f"/commerce/orders/{first}/warehouse-allocations", expected=404)
            self.call(buyer, "sandboxPay", "order/sandboxPay", orderId=first, paymentRequestId="mw_pay_" + self.run_id, scenario="success")
            def ship(name, warehouse, quantity, expected=200):
                return self.api(admin, name, "POST", f"/commerce/orders/{first}/ship",
                                {"requestKey": "mw_" + name + "_" + self.run_id, "warehouseId": warehouse,
                                 "items": [{"productId": pid, "quantity": quantity}], "carrier": "模拟多仓物流",
                                 "trackingNo": "MW-" + name + "-" + self.run_id}, expected)
            ship("cannotStealSecond", b, 3, 409)
            before_erp = self.stock(admin, pid, "beforeBlockedErp")
            blocked = {"systematicReceipt": "MW_BLOCK_" + self.run_id, "receiptCategory": 1,
                       "receiptType": 2, "receiptStatus": 2, "retrievalIds": str(a), "warehousingIds": "0",
                       "details": [{"productId": str(pid), "retrievalId": str(a), "planQuantity": "1"}]}
            self.request(admin, "erpCannotStealAssignedWarehouse", "POST", PURCHASE + "/save", body=blocked, expected=409)
            self.check("failedErpHasNoStockEffect", self.balances(self.stock(admin, pid, "afterBlockedErp")) == self.balances(before_erp))
            self.call(buyer, "cancelSecond", "order/cancelOrder", orderId=second)
            self.call(buyer, "cancelSecondReplay", "order/cancelOrder", orderId=second)
            self.check("cancelReleasesOnce", sum(self.pending(self.allocation(admin, second, "secondReleased")).values()) == 0)
            sent_a = ship("batchA", a, 2)
            ship("batchA", a, 2)
            ship("batchA", b, 2, 409)
            sent_b = ship("batchB", b, 2)
            self.check("partialShipmentsKeepRemainingUnit", sent_a["orderStatus"] == sent_b["orderStatus"] == 1
                       and len(sent_b["shipments"]) == 2)
            self.check("exactRemainingWarehouse", self.pending(self.allocation(admin, first, "afterShipAllocation")) == {a: 1, b: 0})
            self.refund(admin, buyer, first, pid, "UNSHIPPED_REFUND", 1, "unshipped")
            self.check("refundClearsAllCommitments", sum(self.pending(self.allocation(admin, first, "afterRefundAllocation")).values()) == 0)
            self.refund(admin, buyer, first, pid, "RETURN_REFUND", 4, "returned")
            warehouse_facts = self.api(admin, "afterReturnWarehouses", "GET", f"/commerce/inventory/{pid}/warehouses")
            self.check("returnUsesOriginalWarehouses", {int(r["warehouseId"]): int(r["onHand"]) for r in warehouse_facts} == {a: 3, b: 7})
            final_order = self.api(admin, "finalOrder", "GET", f"/commerce/orders/{first}")
            self.check("orderFullyRefunded", float(final_order["totalAmount"]) == float(final_order["refundedAmount"]))
            audit = self.api(admin, "allocationAudit", "GET", f"/commerce/orders/{first}/warehouse-allocation-events")
            self.check("warehouseAllocationAuditBalances", sum(int(r["quantity"]) for r in audit if r["eventType"] == "ALLOCATE") == 5
                       and sum(int(r["quantity"]) for r in audit if r["eventType"] == "DISPATCH") == 4
                       and sum(int(r["quantity"]) for r in audit if r["eventType"] == "RELEASE") == 1)
            self.receipt(admin, pid, {a: 3, b: 7}, "ownedSupplierReturn", 2)
            self.check("ownedStockDrainedViaReceipt", self.stock(admin, pid, "finalOwnStock")["bookStock"] == 0)
            self.api(admin, "unlistOwnedSku", "POST", f"/commerce/products/{pid}/listing", {"listed": False})
            self.request(admin, "disableOwnedSku", "PUT", "/baseDate/product/update", body={**product, "productId": str(pid), "status": "1"})
            for row in selected:
                self.request(admin, "disableOwnedWarehouse." + str(row["warehouseId"]), "PUT", "/baseDate/warehouse/update",
                             body={**row, "warehouseValid": 1})
            reconciliation = self.api(admin, "finalReconciliation", "GET", "/commerce/inventory/reconciliation")
            self.check("reconciliationHealthy", reconciliation["healthy"] is True)
            self.report.update({"success": True, "ownedSkuOnHand": 0, "existingStockUntouched": True,
                                "ownedFixturesRetired": True, "allocationReleaseOnce": True})


if __name__ == "__main__":
    acceptance = MultiWarehouse()
    try:
        acceptance.run()
    except Exception as failure:
        acceptance.report["success"] = False
        acceptance.report["failedCheck"] = str(failure)[:160] if isinstance(failure, SafeFailure) else type(failure).__name__
        acceptance.report["retainedBusinessActionRequired"] = "Resume owned orders/receipts shown in state through business APIs; no SQL resets"
    finally:
        acceptance.report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        target = Path(__file__).resolve().parent.parent / "logs" / "multiwarehouse-result.json"
        target.write_text(json.dumps(acceptance.report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"success": acceptance.report.get("success", False), "checks": len(acceptance.report["checks"]),
                          "failedChecks": [row["name"] for row in acceptance.report["checks"] if not row["passed"]],
                          "report": str(target)}, ensure_ascii=False))
    raise SystemExit(0 if acceptance.report.get("success") else 1)
