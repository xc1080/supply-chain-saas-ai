"""Live local supply execution -> ERP receipt -> owned customer fulfilment.

Record one fictional supplier confirmation; do not call a supplier. ERP receipt
and dispatch post +1/-1 through business APIs, retaining all audit documents.
Payments and carrier handling are local simulations. No SQL or balance resets.
An executed or received plan is never cancelled/deleted to conceal a failure.
"""
from __future__ import annotations

from contextlib import ExitStack
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
import json
import os
from pathlib import Path
import sys
from urllib.parse import urlparse
import uuid

import httpx

from business_catalog import load_scenario


JAVA = os.getenv("FUSION_JAVA_URL", "http://127.0.0.1:8035").rstrip("/")
STORE = os.getenv("FUSION_SMOKE_STORE", "http://127.0.0.1:7050").rstrip("/")
RESULT_PATH = Path(__file__).resolve().parent.parent / "logs" / "supply-execution-result.json"
PURCHASE = "/purchase/purchaseReceiptProcessing"
STOCK_FIELDS = ("bookStock", "reservedStock", "activityStock", "unavailableStock", "availableStock")


class SafeFailure(Exception):
    pass


class Acceptance:
    def __init__(self):
        self.run_id = uuid.uuid4().hex[:12]
        self.report = {
            "runId": self.run_id, "startedAt": datetime.now(timezone.utc).isoformat(),
            "tenantId": "demo", "shopId": "default", "quantity": 1,
            "supplierConfirmation": "RECORDED_FICTIONAL_CONFIRMATION_NO_SUPPLIER_API",
            "paymentProvider": "LOCAL_SANDBOX", "logistics": "SIMULATED",
            "sqlUsed": False, "auditDocumentsRetained": True, "checks": [], "state": {},
        }

    def check(self, name, condition):
        self.report["checks"].append({"name": name, "passed": bool(condition)})
        if not condition:
            raise SafeFailure(name)

    def request(self, client, name, method, path, *, body=None, expected=200):
        response = client.request(method, path, json=body)
        try:
            payload = response.json()
        except ValueError:
            payload = None
        code = payload.get("code") if isinstance(payload, dict) else None
        passed = type(code) is int and code == expected and response.status_code in (200, expected)
        self.report["checks"].append({"name": name, "httpStatus": response.status_code,
                                      "code": code if type(code) is int else None, "expected": expected, "passed": passed})
        if not passed:
            raise SafeFailure(name)
        return payload

    def api(self, client, name, method, path, body=None, expected=200):
        return self.request(client, name, method, path, body=body, expected=expected).get("data")

    def call(self, client, name, path, expected=200, **body):
        return self.api(client, name, "POST", "/api/" + path, body, expected)

    def merchant(self, stack, name):
        client = stack.enter_context(httpx.Client(base_url=JAVA, timeout=45, trust_env=False))
        result = self.request(client, name + ".login", "POST", "/login",
                              body={"username": name, "password": os.getenv("FUSION_SMOKE_PASSWORD", "admin123")})
        self.check(name + ".tokenIssued", isinstance(result.get("token"), str) and bool(result["token"]))
        client.headers.update({"Authorization": "Bearer " + result["token"], "X-Shop-ID": "default"})
        return client

    def visitor(self, stack):
        client = stack.enter_context(httpx.Client(base_url=STORE, timeout=45, trust_env=False))
        self.api(client, "visitor.autoLogin", "GET", "/api/account/autoLogin")
        return client

    def stock(self, admin, pid, name):
        return next(row for row in self.api(admin, name, "GET", "/commerce/inventory") if int(row["productId"]) == pid)

    @staticmethod
    def balances(row):
        return {key: row[key] for key in STOCK_FIELDS}

    def warehouse_balances(self, admin, pid, name):
        payload = self.request(admin, name, "GET", "/inventory/inventoryItemInquiry/list?pageSize=500")
        rows = payload["rows"]
        self.check(name + ".completePage", int(payload["total"]) <= len(rows))
        result = {}
        for row in rows:
            if int(row["productId"]) == pid:
                wid = int(row["warehouseId"])
                result[wid] = result.get(wid, Decimal(0)) + Decimal(str(row["planQuantity"]))
        return result

    def drafts(self, admin, name):
        return self.api(admin, name, "GET", "/commerce/planning/drafts")

    def run(self):
        for base in (JAVA, STORE):
            if urlparse(base).hostname not in {"127.0.0.1", "localhost", "::1"}:
                raise SafeFailure("Supply verification is limited to local services")
        draft_id = None
        execute_attempted = False
        draft_body = None
        order_body = None
        order_attempted = False
        pid = None
        receipt_id = "VERIFY_SUPPLY_" + self.run_id
        self.report["state"]["purchaseReceiptId"] = receipt_id
        with ExitStack() as stack:
            admin = self.merchant(stack, os.getenv("FUSION_SMOKE_USER", "admin"))
            reviewer = self.merchant(stack, "demo_supply_reviewer")
            buyer = self.visitor(stack)
            try:
                identity = self.api(admin, "admin.context", "GET", "/commerce/context")
                reviewer_identity = self.api(reviewer, "reviewer.context", "GET", "/commerce/context")
                self.check("demoTenantIdentities", identity["tenantId"] == "demo" and reviewer_identity["tenantId"] == "demo")
                facts = self.api(admin, "realReplenishment", "GET", "/commerce/planning/replenishment")
                scenario = {row["code"]: row for row in load_scenario()["products"]}
                candidates = [row for row in facts["items"] if row["suggestedQuantity"] > 0
                              and row["onHandStock"] > 0 and row["availableStock"] > 0
                              and row["productCode"] != "LAB-TAPO-T300" and row["productCode"] in scenario]
                self.check("realStockAndReplenishmentCandidateExists", bool(candidates))
                candidates.sort(key=lambda row: (row["productCode"] != "LAB-AQARA-M3", row["onHandStock"], row["productId"]))
                selected = candidates[0]
                pid = int(selected["productId"])
                self.report.update({"productCode": selected["productCode"], "productId": pid,
                                    "realSuggestedQuantity": selected["suggestedQuantity"]})
                before = self.balances(self.stock(admin, pid, "stockBefore"))
                warehouses_before = self.warehouse_balances(admin, pid, "physicalWarehousesBefore")
                self.check("warehouseTotalMatchesInitialBookStock", sum(warehouses_before.values(), Decimal(0)) == before["bookStock"])
                warehouse_rows = self.request(admin, "warehouses", "GET", "/baseDate/warehouse/list?pageSize=500")["rows"]
                valid_warehouses = {int(row["warehouseId"]) for row in warehouse_rows}
                warehouse = next((wid for wid, quantity in sorted(warehouses_before.items()) if quantity > 0 and wid in valid_warehouses), None)
                self.check("existingWarehouseWithPhysicalStock", warehouse is not None)
                self.report["warehouseId"] = warehouse
                product = next(row for row in self.request(admin, "actualErpProduct", "GET", "/baseDate/product/list?pageSize=500")["rows"]
                               if int(row["productId"]) == pid)
                supplier_code = scenario[selected["productCode"]]["simulation"]["supplierCode"]
                supplier = next(row for row in self.request(admin, "actualErpSupplier", "GET", "/baseDate/supplier/list?pageSize=500")["rows"]
                                if row["supplierCode"] == supplier_code)
                sid = str(supplier["supplierId"])
                cost = str(Decimal(str(product["costPrice"])))
                incoming_before = self.api(admin, "incomingBefore", "GET", "/commerce/planning/incoming")
                draft_body = {"requestKey": "supply_" + self.run_id, "items": [{"productId": pid, "quantity": 1}]}
                created = self.api(admin, "draftCreate", "POST", "/commerce/planning/drafts", draft_body)
                draft_id = created["draftId"]
                self.report["state"].update({"draftId": draft_id, "draftStatus": created["status"]})
                self.check("draftPending", created["status"] == "PENDING_APPROVAL")
                review_body = {"requestKey": "supply_review_" + self.run_id, "decision": "APPROVE",
                               "note": "虚构供应执行验收：独立审批一件，采购入库后销售消耗，保留业务审计"}
                approved = self.api(reviewer, "independentApprove", "POST", f"/commerce/planning/drafts/{draft_id}/review", review_body)
                self.report["state"]["draftStatus"] = approved["status"]
                self.check("independentApproval", approved["status"] == "APPROVED"
                           and int(approved["reviewedBy"]) == int(reviewer_identity["userId"])
                           and int(approved["reviewedBy"]) != int(approved["createdBy"]))
                expected_at = (datetime.now().replace(microsecond=0) + timedelta(days=1)).isoformat()
                execute_body = {"requestKey": "supply_execute_" + self.run_id, "warehouseId": warehouse,
                                "sourceReference": "虚构供应商确认录入-验收-" + self.run_id, "expectedAt": expected_at}
                execute_attempted = True
                executed = self.api(admin, "supplierConfirmationRecorded", "POST", f"/commerce/planning/drafts/{draft_id}/execute", execute_body)
                self.report["state"]["draftStatus"] = executed["status"]
                self.check("supplierConfirmedWithoutPosting", executed["status"] == "EXECUTED"
                           and executed["executionStatus"] == "SUPPLIER_CONFIRMED" and len(executed["supplyLines"]) == 1)
                line = executed["supplyLines"][0]
                incoming_id = line["incomingId"]
                self.report["state"]["incomingId"] = incoming_id
                self.check("singleExecutedLine", line["state"] == "EXECUTED" and int(line["quantity"]) == 1 and bool(incoming_id))
                replay = self.api(admin, "executeReplay", "POST", f"/commerce/planning/drafts/{draft_id}/execute", execute_body)
                self.check("executionReplaySameDraftAndIncoming", replay == executed)
                incoming = self.api(admin, "incomingAfterExecution", "GET", "/commerce/planning/incoming")
                new_ids = {row["incomingId"] for row in incoming} - {row["incomingId"] for row in incoming_before}
                self.check("exactlyOneIncomingCreated", new_ids == {incoming_id})
                incoming = next(row for row in incoming if row["incomingId"] == incoming_id)
                self.report["state"]["incomingStatus"] = incoming["status"]
                self.check("confirmedFutureIncoming", incoming["status"] == "CONFIRMED" and incoming["receivedQuantity"] == 0
                           and int(incoming["quantity"]) == 1 and int(incoming["warehouseId"]) == warehouse)
                self.check("executeLeavesPhysicalStockUnchanged", self.balances(self.stock(admin, pid, "stockAfterExecute")) == before)
                receipt_body = {
                    "systematicReceipt": receipt_id, "originalReceipt": "SUPPLY-VERIFY-" + self.run_id,
                    "receiptCategory": 1, "receiptType": 1, "receiptStatus": 2, "invoiceDate": date.today().isoformat(),
                    "warehousingIds": str(warehouse), "retrievalIds": "0", "userIds": str(identity["userId"]),
                    "supplierIds": sid, "customerIds": "0", "deposit": "0", "totalAmount": cost,
                    "receiptNotes": "虚构供应执行采购入库验收一件，随后本地模拟销售，保留凭证，无真实供应商交易",
                    "details": [{"productId": str(pid), "warehousingId": str(warehouse), "retrievalId": "0",
                                 "supplierId": sid, "customerId": "0", "measureUnit": product.get("measureUnit") or "件",
                                 "productSpecifications": product["productSpecifications"], "planQuantity": "1",
                                 "univalence": cost, "discount": "100", "money": cost, "cost": cost,
                                 "currentInventory": str(before["bookStock"]), "actualInventory": str(before["bookStock"] + 1),
                                 "remarks": "虚构供应执行验收一件"}],
                }
                self.request(admin, "erpPurchaseReceiptSave", "POST", PURCHASE + "/save", body=receipt_body)
                self.report["state"]["purchaseStatus"] = "POSTED"
                self.request(admin, "erpPurchaseSaveReplay", "POST", PURCHASE + "/save", body=receipt_body)
                receipt = self.api(admin, "retainedPurchaseReceipt", "GET", PURCHASE + "/" + receipt_id)
                self.check("postedPurchaseEvidence", int(receipt["receiptStatus"]) == 2 and int(receipt["receiptCategory"]) == 1
                           and int(receipt["receiptType"]) == 1 and len(receipt["details"]) == 1
                           and int(receipt["details"][0]["productId"]) == pid
                           and Decimal(str(receipt["details"][0]["planQuantity"])) == 1)
                after_purchase = self.balances(self.stock(admin, pid, "stockAfterPurchase"))
                self.check("purchasePostsOneUnit", after_purchase["bookStock"] == before["bookStock"] + 1
                           and after_purchase["availableStock"] == before["availableStock"] + 1)
                receive_path = f"/commerce/planning/incoming/{incoming_id}/receive"
                linked = self.api(admin, "linkPostedErpReceipt", "POST", receive_path, {"receiptId": receipt_id})
                self.report["state"]["incomingStatus"] = linked["status"]
                self.check("incomingReceived", linked["status"] == "RECEIVED" and int(linked["receivedQuantity"]) == 1)
                linked_replay = self.api(admin, "incomingReceiveReplay", "POST", receive_path, {"receiptId": receipt_id})
                self.check("linkReplayStable", linked_replay == linked)
                self.check("linkDoesNotPostStockAgain", self.balances(self.stock(admin, pid, "stockAfterLink")) == after_purchase)
                final_draft = next(row for row in self.drafts(admin, "draftAfterReceipt") if row["draftId"] == draft_id)
                self.report["state"]["draftStatus"] = final_draft["status"]
                self.check("draftReceiptLinked", final_draft["status"] == "RECEIVED" and final_draft["executionStatus"] == "ERP_RECEIVED"
                           and all(row["state"] == "RECEIVED" for row in final_draft["supplyLines"]))
                address = self.call(buyer, "ownedFictionalAddress", "userAddress/addAddress", addressee="供应执行验收访客",
                                    phone="00000000000", address="虚构地址：供应流转本地验收仓", defaultType=1)["addressId"]
                order_body = {"payMethod": "demo", "addressId": address, "clientRequestId": "supply_order_" + self.run_id,
                              "orderList": [{"productId": str(pid), "buyCount": 1, "propertyValueIds": "default"}]}
                order_attempted = True
                order = self.call(buyer, "ownedCustomerPurchase", "order/postOrder", **order_body)
                oid = order["orderId"]
                self.report["state"].update({"orderId": oid, "orderStatus": 0})
                self.check("customerOrderReplay", self.call(buyer, "customerPurchaseReplay", "order/postOrder", **order_body)["orderId"] == oid)
                detail = self.call(buyer, "ownedOrderDetail", "order/getMyOrderDetail", orderId=oid)
                self.check("customerBoughtOneRealSku", len(detail["orderItemList"]) == 1 and int(detail["orderItemList"][0]["productId"]) == pid
                           and detail["orderItemList"][0]["buyCount"] == 1 and detail["orderStatus"] == 0)
                held = self.balances(self.stock(admin, pid, "stockAfterCustomerOrder"))
                self.check("singleReservation", held["bookStock"] == before["bookStock"] + 1
                           and held["reservedStock"] == before["reservedStock"] + 1 and held["availableStock"] == before["availableStock"])
                paid = self.call(buyer, "localSandboxPayment", "order/sandboxPay", orderId=oid,
                                 paymentRequestId="supply_pay_" + self.run_id, scenario="success")
                self.report["state"]["orderStatus"] = paid["orderStatus"]
                self.check("paymentConfirmed", paid["orderStatus"] == 1 and paid["paymentProvider"] == "LOCAL_SANDBOX")
                ship_body = {"requestKey": "supply_ship_" + self.run_id, "items": [{"productId": pid, "quantity": 1}],
                             "carrier": "Simulated verification carrier", "trackingNo": "SUPPLY-" + self.run_id}
                sent = self.api(admin, "merchantDispatch", "POST", f"/commerce/orders/{oid}/ship", ship_body)
                self.report["state"].update({"orderStatus": sent["orderStatus"], "dispatchReceiptId": sent["receiptId"]})
                self.check("oneDispatch", sent["orderStatus"] == 2 and len(sent["shipments"]) == 1)
                sent_replay = self.api(admin, "dispatchReplay", "POST", f"/commerce/orders/{oid}/ship", ship_body)
                self.check("dispatchReplayPostsOnce", len(sent_replay["shipments"]) == 1 and sent_replay["receiptId"] == sent["receiptId"])
                received = self.call(buyer, "ownedCustomerReceive", "order/receiveOrder", orderId=oid)
                self.report["state"]["orderStatus"] = received["orderStatus"]
                self.check("customerReceived", received["orderStatus"] == 3)
                final_stock = self.balances(self.stock(admin, pid, "stockFinal"))
                self.report["inventoryBefore"] = before
                self.report["inventoryAfter"] = final_stock
                self.check("finalInventoryBalancesRestored", final_stock == before)
                final_warehouses = self.warehouse_balances(admin, pid, "physicalWarehousesFinal")
                self.check("finalWarehouseBalancesRestored", final_warehouses == warehouses_before)
                self.check("warehouseTotalMatchesFinalBookStock", sum(final_warehouses.values(), Decimal(0)) == final_stock["bookStock"])
                warehouse_journal = self.api(admin, "immutableWarehouseLedger", "GET", f"/commerce/inventory/{pid}/warehouse-ledger?limit=200")
                inbound = [row for row in warehouse_journal if row["receipt_id"] == receipt_id]
                outbound = [row for row in warehouse_journal if row["receipt_id"] == sent["receiptId"]]
                self.check("oneInboundAndOutboundJournal", len(inbound) == 1 and int(inbound[0]["delta_quantity"]) == 1
                           and int(inbound[0]["warehouse_id"]) == warehouse and len(outbound) == 1
                           and int(outbound[0]["delta_quantity"]) == -1 and int(outbound[0]["warehouse_id"]) == warehouse)
                stock_journal = self.api(admin, "immutableStockLedger", "GET", f"/commerce/inventory/{pid}/ledger?limit=200")
                self.check("oneDispatchLedgerEvent", len([row for row in stock_journal if row["order_id"] == oid and row["event_type"] == "DISPATCH"]) == 1)
                self.check("purchaseLedgerRetained", len([row for row in stock_journal if row["reason"] == "ERP_SAVE:" + receipt_id]) == 1)
                reconciliation = self.api(admin, "reconciliation", "GET", "/commerce/inventory/reconciliation")
                self.check("reconciliationHealthy", reconciliation["healthy"] is True)
                self.report.update({"status": "PASSED", "inventoryRestored": True, "warehouseBalancesRestored": True,
                                    "purchaseAndDispatchPostedOnce": True, "receiptLinkDidNotDuplicateStock": True,
                                    "reconciliationHealthy": True, "retainedStates": {"draft": "RECEIVED", "incoming": "RECEIVED", "order": 3}})
            except Exception as error:
                self.report.update({"status": "FAILED", "errorType": type(error).__name__,
                                    "failedCheck": str(error) if isinstance(error, SafeFailure) else "Diagnostic suppressed to protect credentials"})
                # Resolve response loss using reads; preserve every executed plan and posted document.
                try:
                    rows = self.drafts(admin, "failureRetainedDraftRead")
                    if draft_id:
                        retained = next((row for row in rows if row["draftId"] == draft_id), None)
                    else:
                        retained = None
                        if draft_body and not execute_attempted:
                            recovered = self.api(admin, "failureCreateResponseRecovery", "POST", "/commerce/planning/drafts", draft_body)
                            draft_id = recovered["draftId"]
                            retained = recovered
                    if retained:
                        self.report["state"].update({"draftId": draft_id, "draftStatus": retained["status"]})
                        if retained["status"] in {"PENDING_APPROVAL", "APPROVED"}:
                            cancelled = self.api(admin, "failureCancelUnexecutedDraft", "POST", f"/commerce/planning/drafts/{draft_id}/cancel",
                                                 {"requestKey": "supply_cleanup_" + self.run_id, "reason": "虚构供应执行验收中断，取消尚未执行草稿并保留审计"})
                            self.report["state"]["draftStatus"] = cancelled["status"]
                        else:
                            self.report["retainedBusinessActionRequired"] = "Resume the executed/received supply or order through business APIs"
                            self.report["retainedSupplyLines"] = [{key: line.get(key) for key in ("productId", "quantity", "state", "incomingId")}
                                                                  for line in retained["supplyLines"]]
                    if pid:
                        self.report["inventoryAtFailure"] = self.balances(self.stock(admin, pid, "failureStockRead"))
                    receipt = self.api(admin, "failurePurchaseRead", "GET", PURCHASE + "/" + receipt_id)
                    self.report["state"]["purchaseStatus"] = "POSTED" if receipt and int(receipt["receiptStatus"]) == 2 else "ABSENT_OR_UNPOSTED"
                    if order_attempted:
                        oid = self.report["state"].get("orderId")
                        if not oid:
                            recovered_order = self.call(buyer, "failureOrderResponseRecovery", "order/postOrder", **order_body)
                            oid = recovered_order["orderId"]
                            self.report["state"]["orderId"] = oid
                        retained_order = self.call(buyer, "failureOwnedOrderRead", "order/getMyOrderDetail", orderId=oid)
                        self.report["state"]["orderStatus"] = retained_order["orderStatus"]
                except Exception as cleanup_error:
                    self.report["stateReadErrorType"] = type(cleanup_error).__name__
                    self.report["retainedBusinessActionRequired"] = "Inspect retained IDs and resume with business APIs; no executed documents were reversed"
        self.report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        self.report["passedChecks"] = sum(row["passed"] for row in self.report["checks"])
        self.report["failedChecks"] = sum(not row["passed"] for row in self.report["checks"])
        RESULT_PATH.parent.mkdir(parents=True, exist_ok=True)
        RESULT_PATH.write_text(json.dumps(self.report, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        summary = {key: value for key, value in self.report.items() if key != "checks"}
        summary["resultPath"] = str(RESULT_PATH)
        print(json.dumps(summary, ensure_ascii=False, separators=(",", ":")))
        return 0 if self.report["status"] == "PASSED" else 1


if __name__ == "__main__":
    try:
        sys.exit(Acceptance().run())
    except Exception as error:
        print(json.dumps({"status": "FAILED", "errorType": type(error).__name__,
                          "diagnostic": str(error) if isinstance(error, SafeFailure) else "Diagnostic suppressed to protect credentials"}))
        sys.exit(1)
