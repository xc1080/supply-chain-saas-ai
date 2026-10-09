"""Verify local incoming stock with retained, fictional ERP procurement evidence.

The fixed request/receipt keys post one sample unit at most across reruns. No SQL,
balance reset or successful document deletion is used by this verification.
"""
from contextlib import ExitStack
from datetime import date
from decimal import Decimal
import json
import os
from pathlib import Path
from urllib.parse import urlparse

import httpx

from business_catalog import load_scenario

BASE = os.getenv("FUSION_JAVA_URL", "http://127.0.0.1:8035").rstrip("/")
RECEIPT = "VERIFY_PLAN_IN_V1"
REQUEST = "planning_incoming_smoke_v1"
PRODUCT = "LAB-TAPO-L530E"
PURCHASE = "/purchase/purchaseReceiptProcessing"


def request(client, method, path, body=None, expected=200):
    response = client.request(method, path, json=body)
    result = response.json()
    actual = result.get("code", response.status_code)
    if actual != expected:
        raise AssertionError({"path": path, "httpStatus": response.status_code,
                              "code": actual, "expected": expected, "message": result.get("msg")})
    return result


def data(client, method, path, body=None, expected=200):
    return request(client, method, path, body, expected).get("data")


def merchant(stack, username):
    client = stack.enter_context(httpx.Client(base_url=BASE, timeout=45, trust_env=False))
    login = request(client, "POST", "/login", {
        "username": username, "password": os.getenv("FUSION_SEED_PASSWORD", "admin123")})
    client.headers.update({"Authorization": "Bearer " + login["token"], "X-Shop-ID": "default"})
    return client


def comparable(rows):
    return sorted(({key: value for key, value in row.items() if key != "version"}
                   for row in rows), key=lambda row: str(row["productId"]))


def run():
    if urlparse(BASE).hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError("Incoming smoke is limited to the local learning deployment")
    sample = next(row for row in load_scenario()["products"] if row["code"] == PRODUCT)
    with ExitStack() as stack:
        demo = merchant(stack, os.getenv("FUSION_DEMO_USER", "admin"))
        studio = merchant(stack, os.getenv("FUSION_STUDIO_USER", "studio_admin"))
        identity = data(demo, "GET", "/commerce/context")
        assert identity["tenantId"] == "demo"
        assert data(studio, "GET", "/commerce/context")["tenantId"] == "studio"
        studio_before = comparable(data(studio, "GET", "/commerce/inventory"))
        studio_incoming_before = data(studio, "GET", "/commerce/planning/incoming")
        target = next(row for row in data(demo, "GET", "/commerce/inventory")
                      if row["productCode"] == PRODUCT)
        pid = int(target["productId"])
        product = next(row for row in request(demo, "GET", "/baseDate/product/list?pageSize=500")["rows"]
                       if int(row["productId"]) == pid)
        warehouse = next(row for row in request(demo, "GET", "/baseDate/warehouse/list?pageSize=500")["rows"]
                         if row["warehouseName"] == "样例·华东中心仓（虚构）")
        wid = int(warehouse["warehouseId"])
        supplier = next(row for row in request(demo, "GET", "/baseDate/supplier/list?pageSize=500")["rows"]
                        if row["supplierCode"] == sample["simulation"]["supplierCode"])
        sid = str(supplier["supplierId"])
        cost = str(Decimal(str(product["costPrice"])))

        def stock():
            return next(row for row in data(demo, "GET", "/commerce/inventory")
                        if int(row["productId"]) == pid)

        def evidence():
            return [row for row in data(demo, "GET", f"/commerce/inventory/{pid}/warehouse-ledger?limit=200")
                    if row["receipt_id"] == RECEIPT]

        incoming_body = {"requestKey": REQUEST, "productId": pid, "warehouseId": wid, "quantity": 1,
                         "sourceReference": "虚构样例采购在途验收-PLANNING_V1",
                         "expectedAt": "2026-10-10T18:00:00"}
        incoming = data(demo, "POST", "/commerce/planning/incoming", incoming_body)
        incoming_id = incoming["incomingId"]
        assert data(demo, "POST", "/commerce/planning/incoming", incoming_body)["incomingId"] == incoming_id
        # Registering an expected delivery cannot make physical goods sellable.
        assert stock()["bookStock"] == target["bookStock"]
        assert stock()["availableStock"] == target["availableStock"]
        existing = data(demo, "GET", PURCHASE + "/" + RECEIPT)
        already_posted = existing is not None
        body = {"systematicReceipt": RECEIPT, "originalReceipt": "PLANNING-SMOKE-V1",
                "receiptCategory": 1, "receiptType": 1, "receiptStatus": 2,
                "invoiceDate": date.today().isoformat(), "warehousingIds": str(wid), "retrievalIds": "0",
                "userIds": str(identity["userId"]), "supplierIds": sid, "customerIds": "0", "deposit": "0",
                "totalAmount": cost, "receiptNotes": "样例在途验收采购；虚构供应，无真实采购。",
                "details": [{"productId": str(pid), "warehousingId": str(wid), "retrievalId": "0",
                             "supplierId": sid, "customerId": "0", "measureUnit": product.get("measureUnit") or "件",
                             "productSpecifications": product["productSpecifications"], "planQuantity": "1",
                             "univalence": cost, "discount": "100", "money": cost, "cost": cost,
                             "currentInventory": str(target["bookStock"]), "actualInventory": str(target["bookStock"] + 1),
                             "remarks": "虚构样例采购验收1件，保留业务凭证"}]}
        if not already_posted:
            request(demo, "POST", PURCHASE + "/save", body)
        receipt = data(demo, "GET", PURCHASE + "/" + RECEIPT)
        assert int(receipt["receiptStatus"]) == 2 and int(receipt["receiptCategory"]) == 1
        assert int(receipt["receiptType"]) == 1
        assert len(receipt["details"]) == 1 and Decimal(str(receipt["details"][0]["planQuantity"])) == 1
        assert int(receipt["details"][0]["productId"]) == pid
        after_purchase = stock()
        expected_delta = 0 if already_posted else 1
        assert after_purchase["bookStock"] == target["bookStock"] + expected_delta
        assert after_purchase["availableStock"] == target["availableStock"] + expected_delta

        linked = data(demo, "POST", f"/commerce/planning/incoming/{incoming_id}/receive", {"receiptId": RECEIPT})
        assert linked["status"] == "RECEIVED" and linked["receivedQuantity"] == 1
        linked_again = data(demo, "POST", f"/commerce/planning/incoming/{incoming_id}/receive", {"receiptId": RECEIPT})
        assert linked_again == linked
        assert comparable([stock()]) == comparable([after_purchase])
        journal = evidence()
        assert len(journal) == 1 and int(journal[0]["delta_quantity"]) == 1
        assert int(journal[0]["warehouse_id"]) == wid

        # Both rejected mutations must leave the linked procurement evidence intact.
        request(demo, "POST", PURCHASE + "/save", {**body, "receiptNotes": "此改写应被关联凭证保护拒绝"}, 409)
        request(demo, "POST", PURCHASE + "/delete", [{"systematicReceipt": RECEIPT}], 409)
        assert data(demo, "GET", PURCHASE + "/" + RECEIPT) == receipt
        assert evidence() == journal
        assert comparable([stock()]) == comparable([after_purchase])

        assert not any(row["incomingId"] == incoming_id for row in data(studio, "GET", "/commerce/planning/incoming"))
        data(studio, "POST", f"/commerce/planning/incoming/{incoming_id}/receive", {"receiptId": RECEIPT}, 404)
        assert data(studio, "GET", PURCHASE + "/" + RECEIPT) is None
        assert comparable(data(studio, "GET", "/commerce/inventory")) == studio_before
        assert data(studio, "GET", "/commerce/planning/incoming") == studio_incoming_before
        assert data(demo, "GET", "/commerce/inventory/reconciliation")["healthy"]
        assert data(studio, "GET", "/commerce/inventory/reconciliation")["healthy"]
        report = {"productCode": PRODUCT, "receiptId": RECEIPT, "incomingId": incoming_id,
                  "alreadyPosted": already_posted, "netInventoryChangeThisRun": expected_delta,
                  "retainedSampleProcurementQuantity": 1, "receivedQuantity": 1,
                  "incomingRegistrationIdempotent": True, "receiptLinkedWithoutDuplicateStock": True,
                  "linkedReceiptRewriteAndDeletionRejected": True, "studioIsolated": True,
                  "reconciliationHealthy": True, "fictionalProcurement": True, "auditRetained": True}
        result_path = Path(__file__).parent.parent / "logs" / "planning-incoming-result.json"
        result_path.parent.mkdir(exist_ok=True)
        result_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False))
        return report


if __name__ == "__main__":
    run()
