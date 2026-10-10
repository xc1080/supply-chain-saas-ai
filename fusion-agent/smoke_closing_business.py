"""Local HTTP/MySQL closure using two run-owned fictional SKUs and one owned visitor.

Retains every receipt, order, carrier observation, refund and expense audit. No SQL,
service lifecycle commands, existing cart mutations or real payment/carrier calls.
Shipping policy is restored only if its current value still equals this run's value.
Run only after Java/Python closing-business changes have been deployed locally.
"""
from __future__ import annotations

from contextlib import ExitStack
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
import json
import os
from pathlib import Path
import sys
from urllib.parse import urlencode, urlparse

import httpx

from smoke_supply_execution import Acceptance, JAVA, STORE, PURCHASE, SafeFailure


RESULT_PATH = Path(__file__).resolve().parent.parent / "logs" / "closing-business-result.json"
AGENT = os.getenv("FUSION_CLOSING_AGENT_URL", STORE).rstrip("/")
ADMIN_WEB = os.getenv("FUSION_CLOSING_ADMIN_WEB_URL", "http://127.0.0.1:5173").rstrip("/")


class ClosingBusiness(Acceptance):
    def __init__(self):
        super().__init__()
        self.report.update(kind="OWNED_CLOSING_BUSINESS", passed=False, status="NOT_RUN",
                           existingSkuChanged=False, existingCartChanged=False, quantity=3,
                           supplierConfirmation="FICTIONAL_SUPPLIER_0_RECEIPTS_ONLY",
                           roleCoverage="SUPPLY_REVIEWER live; exact VIEWER covered by Java tests",
                           proof={})

    @staticmethod
    def money(value):
        try:
            exact = Decimal(str(value))
            if not exact.is_finite():
                raise SafeFailure("Server amount must be finite and exact to cents")
            cents = exact.quantize(Decimal("0.01"))
            if exact != cents:
                raise SafeFailure("Server amount must be finite and exact to cents")
            return cents
        except (InvalidOperation, TypeError, ValueError):
            raise SafeFailure("Server amount must be finite and exact to cents") from None

    def save_report(self):
        self.report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        self.report["passedChecks"] = sum(row["passed"] for row in self.report["checks"])
        self.report["failedChecks"] = sum(not row["passed"] for row in self.report["checks"])
        RESULT_PATH.parent.mkdir(parents=True, exist_ok=True)
        RESULT_PATH.write_text(json.dumps(self.report, ensure_ascii=False, indent=2, default=str), encoding="utf-8")

    def customer_get(self, client, name, path, **params):
        return self.api(client, name, "GET", "/api/" + path + "?" + urlencode(params))

    def stock_pair(self, admin, products, name):
        rows = self.api(admin, name, "GET", "/commerce/inventory")
        return {pid: self.balances(next(row for row in rows if int(row["productId"]) == pid)) for pid in products}

    def chat(self, client, actor, name, order, expected=200):
        response = client.post("/chat", headers={"Authorization": actor.headers["Authorization"]},
                               json={"message": f"分析订单 {order} 的费用结算和库存流转，核验原出库成本与退货。", "history": [], "shopId": "default"})
        self.check(name + ".httpStatus", response.status_code == expected)
        if expected != 200:
            return None
        result = response.json()
        self.check(name + ".jsonObject", isinstance(result, dict))
        return result

    def create_product(self, admin, suffix, price, category, warehouse):
        code = "LAB-CLOSE-" + self.run_id.upper() + "-" + suffix
        body = {"productCode": code, "productName": f"闭环学习货品{suffix} {self.run_id}",
                "productType": str(category), "productSpecifications": "虚构学习规格" + suffix,
                "measureUnit": "件", "status": "0", "costPrice": "18", "univalence": str(price),
                "discount": "1", "upperLimit": "100", "lowerLimit": "0", "defaultWarehouse": str(warehouse),
                "notes": "虚构学习订单闭环；不代表厂商报价、物流或真实收付款"}
        self.request(admin, suffix + ".productCreate", "POST", "/baseDate/product/add", body=body)
        rows = self.request(admin, suffix + ".productFind", "GET", "/baseDate/product/list?" + urlencode({"productCode": code}))["rows"]
        self.check(suffix + ".exactOwnedProduct", len(rows) == 1 and rows[0]["productCode"] == code)
        pid = int(rows[0]["productId"])
        self.check(suffix + ".protectedIdsExcluded", pid not in (27, 28))
        self.api(admin, suffix + ".shopBinding", "POST", f"/commerce/products/{pid}/listing", {"listed": False})
        self.report["state"]["product" + suffix] = {"productId": pid, "productCode": code, "price": price}
        return pid, body

    def purchase(self, admin, suffix, pid, quantity, body, warehouse, user):
        receipt_id = "CLOSE_" + suffix + "_" + self.run_id
        receipt = {"systematicReceipt": receipt_id, "originalReceipt": "CLOSE-" + self.run_id,
                   "receiptCategory": 1, "receiptType": 1, "receiptStatus": 2, "invoiceDate": date.today().isoformat(),
                   "warehousingIds": str(warehouse), "retrievalIds": "0", "userIds": str(user), "supplierIds": "0", "customerIds": "0",
                   "deposit": "0", "totalAmount": str(quantity * 18), "receiptNotes": "本run虚构学习入库，供应方0，不调用供应商",
                   "details": [{"productId": str(pid), "warehousingId": str(warehouse), "retrievalId": "0", "supplierId": "0",
                                "customerId": "0", "measureUnit": "件", "productSpecifications": body["productSpecifications"],
                                "planQuantity": str(quantity), "univalence": "18", "discount": "100", "money": str(quantity * 18),
                                "cost": "18", "remarks": "独立学习货品入库"}]}
        self.request(admin, suffix + ".purchase", "POST", PURCHASE + "/save", body=receipt)
        self.api(admin, suffix + ".listing", "POST", f"/commerce/products/{pid}/listing", {"listed": True})
        self.report["state"]["purchase" + suffix] = receipt_id
        posted = self.api(admin, suffix + ".retainedReceipt", "GET", PURCHASE + "/" + receipt_id)
        self.check(suffix + ".postedOwnedReceipt", int(posted["receiptStatus"]) == 2 and len(posted["details"]) == 1
                   and int(posted["details"][0]["productId"]) == pid and self.money(posted["details"][0]["planQuantity"]) == quantity)

    def run(self):
        for base in (JAVA, STORE, AGENT):
            if urlparse(base).hostname not in {"127.0.0.1", "localhost", "::1"}:
                raise SafeFailure("Closing verification is limited to local services")
        self.report["status"] = "RUNNING"
        with ExitStack() as stack:
            admin = self.merchant(stack, os.getenv("FUSION_SMOKE_USER", "admin"))
            foreign = self.merchant(stack, "studio_admin")
            nonfinance = self.merchant(stack, "demo_supply_reviewer")
            buyer, other_buyer = self.visitor(stack), self.visitor(stack)
            agent = stack.enter_context(httpx.Client(base_url=AGENT, timeout=120, trust_env=False))
            old_policy, promotion_id, promotion_body = None, None, None
            shipping_attempted = False
            primary_error = None
            order_id = None
            try:
                identity = self.api(admin, "adminContext", "GET", "/commerce/context")
                other_identity = self.api(foreign, "foreignContext", "GET", "/commerce/context")
                self.check("independentTenantIdentities", identity["tenantId"] == "demo" and other_identity["tenantId"] != "demo")
                roles = self.api(nonfinance, "nonFinanceMembership", "GET", "/commerce/shops")
                selected_role = next(row for row in roles if row["shopId"] == "default")
                self.check("actualNonFinanceRole", selected_role["memberRole"] == "SUPPLY_REVIEWER"
                           and not {"REFUND_REVIEW", "REFUND_EXECUTE"}.intersection(selected_role["capabilities"]))
                buyer_identity = self.call(buyer, "ownedVisitorIdentity", "account/getUserInfo")
                other_buyer_identity = self.call(other_buyer, "otherVisitorIdentity", "account/getUserInfo")
                self.check("independentOwnedVisitors", buyer_identity["identityType"] == other_buyer_identity["identityType"] == "GUEST"
                           and buyer_identity["tenantId"] == other_buyer_identity["tenantId"] == "demo"
                           and bool(buyer_identity["userId"]) and buyer_identity["userId"] != other_buyer_identity["userId"])
                protected_before = {int(row["productId"]): self.balances(row) for row in self.api(admin, "protectedBefore", "GET", "/commerce/inventory") if int(row["productId"]) in (27, 28)}
                cart_before = self.call(buyer, "ownedCartBefore", "productCart/loadCart")["list"]
                categories = self.api(admin, "categories", "GET", "/baseDate/product/productTypeTree?status=0")
                def leaves(rows):
                    for row in rows:
                        if row.get("children"):
                            yield from leaves(row["children"])
                        elif int(row.get("id", 0)) > 0:
                            yield int(row["id"])
                category = next(leaves(categories))
                warehouses = self.request(admin, "warehouses", "GET", "/baseDate/warehouse/list?pageSize=500")["rows"]
                warehouse = int(next(row for row in warehouses if int(row.get("warehouseValid", 0)) == 0)["warehouseId"])
                a, a_body = self.create_product(admin, "A", 99, category, warehouse)
                b, b_body = self.create_product(admin, "B", 129, category, warehouse)
                self.purchase(admin, "A", a, 6, a_body, warehouse, identity["userId"])
                self.purchase(admin, "B", b, 3, b_body, warehouse, identity["userId"])
                self.report["state"]["warehouseId"] = warehouse
                initial = self.stock_pair(admin, (a, b), "ownedInitialStock")
                self.check("ownedInboundBalances", initial[a]["bookStock"] == 6 and initial[b]["bookStock"] == 3)
                old_policy = self.api(admin, "pricingPolicyBefore", "GET", "/commerce/pricing/policy")
                self.report["proof"]["shippingPolicyBefore"] = old_policy
                shipping_attempted = True
                self.api(admin, "temporaryShippingPolicy", "PUT", "/commerce/pricing/policy", {"shippingFee": "9.99"})
                promotion_body = {"title": "闭环专属学习优惠 " + self.run_id, "discountAmount": "20.01", "productIds": [a, b],
                                  "startsAt": (datetime.now() - timedelta(minutes=1)).isoformat(timespec="seconds"),
                                  "endsAt": (datetime.now() + timedelta(hours=2)).isoformat(timespec="seconds"), "enabled": True}
                promotion_id = self.api(admin, "ownedPromotion", "POST", "/commerce/pricing/promotions", promotion_body)["promotionId"]
                self.report["state"]["promotionId"] = promotion_id
                order_lines = [{"productId": str(a), "buyCount": 2, "price": .01}, {"productId": str(b), "buyCount": 1, "price": .01}]
                quote = self.call(buyer, "customerServerQuote", "pricing/quote", orderList=order_lines, promotionId=promotion_id,
                                  shippingFee=.01, discountAmount=.01, totalAmount=.01)
                for key, amount in {"originalAmount": "327.00", "discountAmount": "20.01", "shippingAmount": "9.99", "payableAmount": "316.98"}.items():
                    self.check("quote." + key, self.money(quote[key]) == Decimal(amount))
                line_quote = {int(row["productId"]): row for row in quote["items"]}
                self.check("quoteExactLineAllocation", len(quote["items"]) == 2 and set(line_quote) == {a, b}
                           and line_quote[a]["quantity"] == 2 and line_quote[b]["quantity"] == 1
                           and self.money(line_quote[a]["payableAmount"]) == Decimal("191.93")
                           and self.money(line_quote[b]["payableAmount"]) == Decimal("125.05"))
                self.check("quoteDoesNotReserve", self.stock_pair(admin, (a, b), "stockAfterQuote") == initial)
                address = self.call(buyer, "ownedAddress", "userAddress/addAddress", addressee="闭环学习访客", phone="00000000000",
                                    address="虚构地址：独立订单费用与售后学习验收", defaultType=1)["addressId"]
                order_args = {"payMethod": "demo", "addressId": address, "clientRequestId": "closing_" + self.run_id,
                              "orderFrom": "1", "orderList": order_lines, "promotionId": promotion_id, "amount": .01}
                order_id = self.call(buyer, "ownedOrderCreate", "order/postOrder", **order_args)["orderId"]
                self.report["state"]["orderId"] = self.report["orderId"] = order_id
                self.report.update(customerUrl=f"http://127.0.0.1:6001/order/{order_id}", adminUrl=ADMIN_WEB + "/commerce/orders")
                held = self.stock_pair(admin, (a, b), "ownedHeldStock")
                self.check("onlyOwnedOrderHeld", held[a]["reservedStock"] == 2 and held[b]["reservedStock"] == 1)
                self.check("orderIdempotent", self.call(buyer, "ownedOrderReplay", "order/postOrder", **order_args)["orderId"] == order_id)
                self.check("orderReplayDoesNotDuplicateHold", self.stock_pair(admin, (a, b), "stockAfterOrderReplay") == held)
                detail = self.call(buyer, "ownedPriceSnapshot", "order/getMyOrderDetail", orderId=order_id)
                self.check("clientPriceIgnoredAndServerSnapshotSaved", self.money(detail["amount"]) == Decimal("316.98")
                           and detail["amountSnapshotVersion"] == 1
                           and all(self.money(detail["amountBreakdown"][key]) == self.money(quote["amountBreakdown"][key])
                                   for key in ("originalAmount", "discountAmount", "shippingAmount", "payableAmount")))
                self.report["proof"]["quote"] = quote
                paid = self.call(buyer, "sandboxPay", "order/sandboxPay", orderId=order_id, paymentRequestId="closing_pay_" + self.run_id, scenario="success")
                self.check("sandboxPaymentConfirmed", paid["orderStatus"] == 1 and paid["paymentProvider"] == "LOCAL_SANDBOX")
                shipment_ids = []
                for suffix, lines in [("first", [{"productId": a, "quantity": 1}]),
                                      ("second", [{"productId": b, "quantity": 1}, {"productId": a, "quantity": 1}])]:
                    body = {"requestKey": "closing_ship_" + suffix + "_" + self.run_id, "warehouseId": warehouse,
                            "items": lines, "carrier": "Local simulated carrier", "trackingNo": "CLOSE-" + suffix + "-" + self.run_id}
                    sent = self.api(admin, suffix + ".dispatch", "POST", f"/commerce/orders/{order_id}/ship", body)
                    count = len(sent["shipments"])
                    replay = self.api(admin, suffix + ".dispatchReplay", "POST", f"/commerce/orders/{order_id}/ship", body)
                    self.check(suffix + ".dispatchExactlyOnce", len(replay["shipments"]) == count)
                    newly_created = [row["shipmentId"] for row in sent["shipments"] if row["shipmentId"] not in shipment_ids]
                    self.check(suffix + ".oneNewShipment", len(newly_created) == 1)
                    shipment_ids.append(newly_created[0])
                    self.check(suffix + ".orderProgress", sent["orderStatus"] == (1 if suffix == "first" else 2))
                self.check("twoIndependentShipments", len(set(shipment_ids)) == 2)
                shipment_tracking = []
                for index, shipment in enumerate(shipment_ids):
                    empty = self.customer_get(buyer, f"shipment{index}.emptyTracking", "order/shipmentTracking", shipmentId=shipment)
                    self.check(f"shipment{index}.noInventedEvents", empty["shipmentId"] == shipment and empty["orderId"] == order_id
                               and empty["source"] == "SIMULATED" and empty["provider"] == "LOCAL_SIMULATED"
                               and empty["queryStatus"] == "AVAILABLE" and empty["status"] == "NO_OBSERVATION" and not empty["events"])
                    node = {"requestKey": "node_" + self.run_id, "status": "DELIVERED", "occurredAt": datetime.now().isoformat(timespec="seconds"),
                            "location": "本地模拟节点", "description": "本run商家记录模拟送达，不代表真实承运商"}
                    recorded = self.api(admin, f"shipment{index}.recordNode", "POST", f"/commerce/shipments/{shipment}/tracking/sandbox-events", node)
                    replayed_node = self.api(admin, f"shipment{index}.recordNodeReplay", "POST", f"/commerce/shipments/{shipment}/tracking/sandbox-events", node)
                    observed = self.customer_get(buyer, f"shipment{index}.tracking", "order/shipmentTracking", shipmentId=shipment)
                    self.check(f"shipment{index}.explicitSimulatedDelivered", observed["shipmentId"] == shipment and observed["orderId"] == order_id
                               and observed["source"] == "SIMULATED" and observed["provider"] == "LOCAL_SIMULATED"
                               and observed["queryStatus"] == "AVAILABLE" and observed["status"] == "DELIVERED"
                               and len(observed["events"]) == 1 and observed["events"] == recorded["events"] == replayed_node["events"]
                               and observed["events"][0]["status"] == node["status"]
                               and datetime.fromisoformat(observed["events"][0]["occurredAt"]) == datetime.fromisoformat(node["occurredAt"])
                               and bool(observed["observedAt"]))
                    shipment_tracking.append(observed)
                unchanged = self.call(buyer, "orderAfterProviderObservation", "order/getMyOrderDetail", orderId=order_id)
                self.check("providerDoesNotSignForCustomer", unchanged["orderStatus"] == 2)
                received = self.call(buyer, "customerExplicitReceive", "order/receiveOrder", orderId=order_id)
                self.check("customerReceiptConfirmed", received["orderStatus"] == 3)
                application = {"orderId": order_id, "requestKey": "closing_case_" + self.run_id, "reason": "虚构学习验收：A购买两件退一件",
                               "kind": "RETURN_REFUND", "items": [{"productId": a, "quantity": 1}]}
                case = self.call(buyer, "applyOneAReturn", "afterSales/apply", **application)
                case_id = case["afterSalesId"]
                self.report["state"]["afterSalesId"] = case_id
                self.check("refundIncludesExactDiscountAndShippingShare", self.money(case["refundAmount"]) == Decimal("95.97") and case["returnEvidenceRequired"] is True)
                self.api(admin, "approveReturn", "POST", f"/commerce/after-sales/{case_id}/review", {"requestKey": "review_" + self.run_id, "decision": "APPROVE"})
                before_return = self.stock_pair(admin, (a, b), "stockBeforeReturnRegistration")
                accept = {"requestKey": "accept_" + self.run_id, "condition": "SELLABLE"}
                self.api(admin, "newCaseCannotAcceptWithoutEvidence", "POST", f"/commerce/after-sales/{case_id}/accept-return", accept, 409)
                parcel = {"afterSalesId": case_id, "carrierCode": "LOCAL_TEST", "trackingNo": "RETURN-" + self.run_id, "requestKey": "parcel_" + self.run_id}
                registered = self.call(buyer, "registerReturnParcel", "afterSales/returnParcel", **parcel)
                repeated = self.call(buyer, "registerReturnParcelReplay", "afterSales/returnParcel", **parcel)
                self.check("returnRegistrationOwnedEvidence", registered["registered"] is True and registered["status"] == "REGISTERED"
                           and registered["afterSalesId"] == case_id and registered["orderId"] == order_id
                           and registered["carrierCode"] == parcel["carrierCode"] and registered["trackingNo"] == parcel["trackingNo"]
                           and bool(registered["registeredAt"]) and registered["tracking"]["queryStatus"] == "AVAILABLE"
                           and registered["tracking"]["status"] == "NO_OBSERVATION" and not registered["tracking"]["events"])
                self.check("returnRegistrationIdempotent", all(registered[key] == repeated[key]
                           for key in ("registered", "status", "afterSalesId", "orderId", "carrierCode", "trackingNo", "registeredAt")))
                self.call(buyer, "differentReturnParcelRejected", "afterSales/returnParcel", expected=409, **{**parcel, "trackingNo": "CHANGED-" + self.run_id})
                self.call(other_buyer, "otherOwnerCannotReadCase", "afterSales/detail", expected=404, afterSalesId=case_id)
                self.api(other_buyer, "otherOwnerCannotReadTracking", "GET", "/api/order/shipmentTracking?" + urlencode({"shipmentId": shipment_ids[0]}), expected=404)
                self.check("registrationDoesNotRestock", self.stock_pair(admin, (a, b), "stockAfterReturnRegistration") == before_return)
                accepted = self.api(admin, "acceptSellableReturn", "POST", f"/commerce/after-sales/{case_id}/accept-return", accept)
                self.report["state"]["returnReceiptId"] = accepted["returnReceiptId"]
                accepted_stock = self.stock_pair(admin, (a, b), "stockAfterAcceptance")
                self.api(admin, "acceptReturnReplay", "POST", f"/commerce/after-sales/{case_id}/accept-return", accept)
                self.check("acceptanceRestocksOnce", self.stock_pair(admin, (a, b), "stockAfterAcceptanceReplay") == accepted_stock
                           and accepted_stock == {pid: {"bookStock": quantity, "reservedStock": 0, "activityStock": 0,
                                                       "unavailableStock": 0, "availableStock": quantity}
                                                  for pid, quantity in ((a, 5), (b, 2))})
                refunded = self.api(admin, "sandboxRefund", "POST", f"/commerce/after-sales/{case_id}/sandbox-refund", {"requestKey": "refund_" + self.run_id, "scenario": "success"})
                self.check("partialRefundConfirmed", refunded["status"] == "REFUNDED" and self.money(refunded["refundedAmount"]) == Decimal("95.97")
                           and refunded["paymentProvider"] == "LOCAL_SANDBOX" and refunded["refundOperation"]["kind"] == "REFUND"
                           and refunded["refundOperation"]["provider"] == "LOCAL_SANDBOX"
                           and refunded["refundOperation"]["outcome"] == "SUCCEEDED" and refunded["refundOperation"]["currency"] == "CNY"
                           and self.money(refunded["refundOperation"]["amount"]) == Decimal("95.97"))
                self.check("refundDoesNotRestock", self.stock_pair(admin, (a, b), "stockAfterRefund") == accepted_stock)
                settlement_path = f"/commerce/settlements/orders/{order_id}"
                expense_ids = []
                for category, value in (("LOGISTICS", "12.50"), ("PACKAGING", "2.50")):
                    expense_body = {"requestKey": "expense_" + category + "_" + self.run_id, "category": category, "amount": value,
                                    "payee": "虚构学习收款方", "evidenceReference": "LOCAL-" + category + "-" + self.run_id}
                    self.api(nonfinance, "nonFinanceRoleCannotWrite." + category, "POST", settlement_path + "/expenses", expense_body, 403)
                    result = self.api(admin, category + ".expense", "POST", settlement_path + "/expenses", expense_body)
                    expense = next(row for row in result["expenses"] if row["category"] == category)
                    expense_ids.append(expense["expenseId"])
                    self.api(admin, category + ".approve", "POST", f"/commerce/settlements/expenses/{expense['expenseId']}/review", {"requestKey": "approve_" + category + "_" + self.run_id, "decision": "APPROVE"})
                before_bad = self.api(admin, "settlementBeforeBadAllocation", "GET", settlement_path)
                bad = {"requestKey": "bad_allocation_" + self.run_id, "evidenceReference": "BAD-" + self.run_id,
                       "allocations": [{"expenseId": expense_ids[0], "amount": "12.51"}, {"expenseId": expense_ids[1], "amount": "2.50"}]}
                self.api(admin, "overAllocationRejected", "POST", settlement_path + "/payments", bad, 409)
                self.check("badAllocationWritesNothing", self.api(admin, "settlementAfterBadAllocation", "GET", settlement_path) == before_bad)
                payment = {"requestKey": "settle_" + self.run_id, "evidenceReference": "LOCAL-SETTLEMENT-" + self.run_id,
                           "allocations": [{"expenseId": expense_ids[0], "amount": "12.50"}, {"expenseId": expense_ids[1], "amount": "2.50"}]}
                self.api(nonfinance, "nonFinanceRoleCannotPay", "POST", settlement_path + "/payments", payment, 403)
                settled = self.api(admin, "onePaymentTwoAllocations", "POST", settlement_path + "/payments", payment)
                self.check("expensePaymentReplayWritesNothing", self.api(admin, "expensePaymentReplay", "POST", settlement_path + "/payments", payment) == settled)
                self.api(admin, "differentRequestCannotOverpay", "POST", settlement_path + "/payments", {**payment, "requestKey": "overpay_" + self.run_id}, 409)
                self.check("expensePaymentOnceAndFullyAllocated", len(settled["payments"]) == 1 and len(settled["payments"][0]["allocations"]) == 2
                           and settled["payments"][0]["provider"] == "LOCAL_SANDBOX" and settled["payments"][0]["status"] == "SUCCESS"
                           and self.money(settled["payments"][0]["amount"]) == Decimal("15.00")
                           and {row["expenseId"]: self.money(row["amount"]) for row in settled["payments"][0]["allocations"]}
                               == {expense_ids[0]: Decimal("12.50"), expense_ids[1]: Decimal("2.50")}
                           and self.money(settled["settledExpenseAmount"]) == Decimal("15.00") and self.money(settled["outstandingExpenseAmount"]) == 0)
                snapshot_body = {"requestKey": "snapshot_" + self.run_id}
                snapshot = self.api(admin, "settlementSnapshot", "POST", settlement_path + "/snapshots", snapshot_body)
                self.check("snapshotReplay", self.api(admin, "settlementSnapshotReplay", "POST", settlement_path + "/snapshots", snapshot_body) == snapshot)
                self.report["state"]["snapshotId"] = snapshot["snapshotId"]
                stock_flow = self.api(admin, "stockExplanation", "GET", settlement_path + "/stock-explanation")
                operations = stock_flow["warehouseEvents"]
                self.check("stockExplanationHasDispatchAndReturn", len(operations) == 4
                           and {int(row["productId"]) for row in operations} == {a, b}
                           and {row["operation"] for row in operations} == {"DISPATCH", "RETURN_ACCEPT"}
                           and all(int(row["deltaQuantity"]) == -1 for row in operations if row["operation"] == "DISPATCH")
                           and sum(row["operation"] == "RETURN_ACCEPT" and int(row["productId"]) == a
                                   and int(row["deltaQuantity"]) == 1 and row["receiptId"] == accepted["returnReceiptId"] for row in operations) == 1
                           and sum(int(row["deltaQuantity"]) for row in operations if int(row["productId"]) == a) == -1
                           and sum(int(row["deltaQuantity"]) for row in operations if int(row["productId"]) == b) == -1
                           and all(int(row["warehouseId"]) == warehouse for row in operations) and stock_flow["writes"] is False)
                cost_ledger = self.api(admin, "retainedOriginalDispatchCosts", "GET", "/commerce/costs/ledger?limit=200")
                costs = [row for row in cost_ledger["entries"] if row["orderId"] == order_id]
                outbound_costs = {int(row["entryId"]): row for row in costs if row["eventType"] == "DISPATCH"}
                returns = [row for row in costs if row["eventType"] == "RETURN"]
                self.check("sellableReturnUsesOriginalWarehouseCost", len(outbound_costs) == 3 and len(returns) == 1
                           and all(self.money(row["unitCost"]) == Decimal("18.00") and int(row["warehouseId"]) == warehouse for row in costs)
                           and int(returns[0]["originEntryId"]) in outbound_costs
                           and int(returns[0]["productId"]) == a and int(outbound_costs[int(returns[0]["originEntryId"])]["productId"]) == a
                           and returns[0]["sourceId"] == case_id and returns[0]["receiptId"] == accepted["returnReceiptId"]
                           and returns[0]["costBasis"] == "ORIGINAL_DISPATCH" and int(returns[0]["quantity"]) == 1
                           and {row["sourceId"] for row in outbound_costs.values()} == set(shipment_ids)
                           and all(int(row["quantity"]) == -1 and row["costBasis"] == "DISPATCH_DOCUMENT" for row in outbound_costs.values())
                           and sum(int(row["quantity"]) for row in costs if int(row["productId"]) == a) == -1
                           and sum(int(row["quantity"]) for row in costs if int(row["productId"]) == b) == -1
                           and sum(self.money(row["amount"]) for row in costs) == Decimal("-36.00"))
                self.api(foreign, "foreignTenantCannotReadFinancialOrder", "GET", settlement_path, expected=404)
                self.api(foreign, "foreignTenantCannotReadStockOrder", "GET", settlement_path + "/stock-explanation", expected=404)
                self.api(nonfinance, "nonFinanceRoleCannotReadFinancialOrder", "GET", settlement_path, expected=403)
                self.chat(agent, nonfinance, "nonFinanceRoleCannotReviewProfit", order_id, 403)
                before_agent = self.api(admin, "settlementBeforeAgent", "GET", settlement_path)
                agent_result = self.chat(agent, admin, "actualAgentOrderReview", order_id)
                plan = agent_result.get("businessPlan", {})
                self.check("agentUsesReadOnlyOrderReview", plan.get("type") == "ORDER_REVIEW" and plan.get("orderId") == order_id
                           and plan.get("writes") is False and plan.get("autonomousExecution") is False
                           and {row["step"] for row in agent_result["trace"]}.issuperset({"read_order_stock", "read_order_settlement"}))
                self.check("agentFactsMatchAuthorizedEvidence", plan["facts"]["stock"] == stock_flow
                           and all(plan["facts"]["settlement"][key] == before_agent[key]
                                   for key in ("orderId", "currency", "provider", "externalChannel", "costComplete", "moneyComplete", "resultStatus"))
                           and all(self.money(plan["facts"]["settlement"][key]) == self.money(before_agent[key])
                                   for key in ("grossPaid", "refundedAmount", "netReceipts", "netStockCost", "approvedExpenseAmount",
                                               "settledExpenseAmount", "outstandingExpenseAmount", "operatingResult")))
                after_agent = self.api(admin, "settlementAfterAgent", "GET", settlement_path)
                self.check("agentDoesNotWriteExpensesPaymentsOrStock", before_agent == after_agent
                           and self.api(admin, "stockExplanationAfterAgent", "GET", settlement_path + "/stock-explanation") == stock_flow
                           and self.stock_pair(admin, (a, b), "stockAfterAgent") == accepted_stock)
                for key, value in {"grossPaid": "316.98", "refundedAmount": "95.97", "netReceipts": "221.01", "netStockCost": "36.00",
                                   "approvedExpenseAmount": "15.00", "settledExpenseAmount": "15.00", "outstandingExpenseAmount": "0.00", "operatingResult": "170.01"}.items():
                    self.check("settlement." + key, self.money(after_agent[key]) == Decimal(value))
                self.check("completeClosedSettlement", after_agent["resultStatus"] == "CLOSED" and after_agent["costComplete"] is True and after_agent["moneyComplete"] is True)
                protected_after = {int(row["productId"]): self.balances(row) for row in self.api(admin, "protectedAfter", "GET", "/commerce/inventory") if int(row["productId"]) in (27, 28)}
                self.check("protected27And28Unchanged", protected_after == protected_before)
                self.check("ownedCartUnchanged", self.call(buyer, "ownedCartAfter", "productCart/loadCart")["list"] == cart_before)
                self.report["proof"].update(stockBefore=initial, stockAfter=accepted_stock, stockExplanation=stock_flow,
                                            settlement=after_agent, snapshot=snapshot, agent=agent_result, orderCosts=costs,
                                            shipmentTracking=shipment_tracking, returnParcel=registered, acceptedReturn=accepted, refundedCase=refunded,
                                            protectedProductsBefore=protected_before, protectedProductsAfter=protected_after)
                self.report["state"].update(orderStatus=received["orderStatus"], afterSalesStatus=refunded["status"], shipmentIds=shipment_ids, expenseIds=expense_ids)
            except BaseException as error:
                primary_error = error
                self.report.update(status="FAILED", errorType=type(error).__name__,
                                   failedCheck=str(error) if isinstance(error, SafeFailure) else "See retained IDs; diagnostic suppressed to protect credentials")
            finally:
                cleanup_errors = []
                # Promotion creation has no request key: recover a lost response by the unique
                # run title plus exact owned products, instead of creating a second promotion.
                if promotion_body is not None and promotion_id is None:
                    try:
                        candidates = [row for row in self.api(admin, "recoverOwnedPromotion", "GET", "/commerce/pricing/promotions/manage")
                                      if row["title"] == promotion_body["title"] and set(map(int, row["productIds"])) == set(promotion_body["productIds"])
                                      and self.money(row["discountAmount"]) == Decimal("20.01")]
                        self.check("ownedPromotionRecoveryUnambiguous", len(candidates) <= 1)
                        if candidates:
                            promotion_id = candidates[0]["promotionId"]
                            self.report["state"]["promotionId"] = promotion_id
                    except BaseException as error:
                        cleanup_errors.append(error)
                if promotion_id is not None:
                    try:
                        disabled = self.api(admin, "disableOwnedPromotion", "PUT", "/commerce/pricing/promotions/" + promotion_id, {**promotion_body, "enabled": False})
                        self.check("ownedPromotionDisabled", disabled["enabled"] is False)
                        self.report["state"]["promotionDisabled"] = True
                    except BaseException as error:
                        cleanup_errors.append(error)
                if shipping_attempted:
                    try:
                        current = self.api(admin, "pricingPolicyBeforeRestore", "GET", "/commerce/pricing/policy")
                        self.check("shippingPolicyStillOwnedTemporaryValue", self.money(current["shippingFee"]) == Decimal("9.99"))
                        restored = self.api(admin, "restoreOriginalShippingPolicy", "PUT", "/commerce/pricing/policy", {"shippingFee": str(self.money(old_policy["shippingFee"]))})
                        self.check("shippingPolicyRestored", self.money(restored["shippingFee"]) == self.money(old_policy["shippingFee"]))
                        self.report["proof"]["shippingPolicyRestored"] = restored
                        self.report["state"]["shippingPolicyRestored"] = True
                    except BaseException as error:
                        cleanup_errors.append(error)
                if cleanup_errors:
                    self.report.update(status="FAILED", cleanupErrorTypes=[type(error).__name__ for error in cleanup_errors],
                                       cleanupActionRequired="Inspect retained policy/promotion state; concurrent shipping changes were not overwritten")
                self.save_report()
                if cleanup_errors:
                    raise SafeFailure("Closing cleanup failed; retained report includes original and cleanup failures") from cleanup_errors[0]
            if primary_error is not None:
                raise primary_error
            preserved = self.call(buyer, "snapshotAfterPolicyRestore", "order/getMyOrderDetail", orderId=order_id)
            self.check("orderPricePreservedAfterPromotionDisableAndPolicyRestore", preserved["orderStatus"] == 3
                       and self.money(preserved["refundedAmount"]) == Decimal("95.97") and self.money(preserved["amount"]) == Decimal("316.98")
                       and self.money(preserved["amountBreakdown"]["discountAmount"]) == Decimal("20.01")
                       and self.money(preserved["amountBreakdown"]["shippingAmount"]) == Decimal("9.99"))
            self.report.update(passed=True, status="PASSED")
            self.save_report()


if __name__ == "__main__":
    scenario = ClosingBusiness()
    try:
        scenario.run()
    except BaseException as error:
        scenario.report.update(passed=False, status="FAILED", errorType=type(error).__name__)
        scenario.report.setdefault("failedCheck", str(error) if isinstance(error, SafeFailure) else "Diagnostic suppressed to protect credentials")
        scenario.save_report()
        print(json.dumps({"status": "FAILED", "resultPath": str(RESULT_PATH), "state": scenario.report["state"]}, ensure_ascii=False))
        sys.exit(1)
    print(json.dumps({"status": scenario.report["status"], "passedChecks": scenario.report["passedChecks"],
                      "resultPath": str(RESULT_PATH), "state": scenario.report["state"],
                      "customerUrl": scenario.report["customerUrl"], "adminUrl": scenario.report["adminUrl"]}, ensure_ascii=False))
