"""Live MySQL receipt -> customer order -> sandbox pay -> warehouse dispatch.

Post one new Studio purchase unit and dispatch that unit, leaving the original
stock balances unchanged. Keep both business documents and immutable journals.
There is no real charge, supplier transaction or carrier submission.
"""
from contextlib import closing
from datetime import date
import json
from pathlib import Path
import uuid

from bootstrap_tenants import sql
from smoke_saas import admin, business, store, call


def balances(rows):
    return [{key: value for key, value in row.items() if key != "version"} for row in rows]


def run():
    run_id = uuid.uuid4().hex[:12]
    receipt = "VERIFY_IN_" + run_id
    purchase_path = "/purchase/purchaseReceiptProcessing"
    order_id = None
    posted = shipped = False
    with closing(admin("admin")) as platform, closing(admin("studio_admin")) as merchant, closing(store()) as customer:
        root_before = balances(business(platform, "GET", "/commerce/inventory"))
        before = balances(business(merchant, "GET", "/commerce/inventory"))
        product = next(row for row in before if row["productCode"] == "DEMO-LAMP-ZB")
        pid = int(product["productId"])
        assert product["availableStock"] > 0
        warehouse = int(sql(f"SELECT warehouse_id FROM ksdatabase_studio.inventory_product WHERE product_id={pid} AND warehouse_id>0 AND plan_quantity>0 ORDER BY warehouse_id,inventory_id LIMIT 1"))
        warehouse_before = sql(f"SELECT warehouse_id,SUM(plan_quantity) FROM ksdatabase_studio.inventory_product WHERE product_id={pid} GROUP BY warehouse_id ORDER BY warehouse_id")
        payload = {
            "systematicReceipt": receipt, "originalReceipt": "Verification " + run_id,
            "receiptCategory": 1, "receiptType": 1, "receiptStatus": 2,
            "invoiceDate": date.today().isoformat(), "warehousingIds": str(warehouse),
            "retrievalIds": "0", "userIds": "101", "supplierIds": "0", "customerIds": "0",
            "deposit": "0", "totalAmount": "70", "receiptNotes": "Sandbox inventory flow verification",
            "details": [{"productId": str(pid), "warehousingId": str(warehouse), "retrievalId": "0",
                         "supplierId": "0", "customerId": "0", "measureUnit": "件",
                         "productSpecifications": "Zigbee/9W", "planQuantity": "1",
                         "univalence": "70", "discount": "100", "money": "70", "cost": "70",
                         "currentInventory": str(product["bookStock"]), "actualInventory": str(product["bookStock"] + 1),
                         "remarks": "Verification unit"}]
        }

        def current():
            return next(row for row in business(merchant, "GET", "/commerce/inventory") if row["productId"] == pid)

        def healthy():
            report = business(merchant, "GET", "/commerce/inventory/reconciliation")
            assert report["healthy"], report

        try:
            response = merchant.post(purchase_path + "/save", json=payload).json()
            assert response.get("code") == 200, response
            posted = True
            assert current()["bookStock"] == product["bookStock"] + 1
            response = merchant.post(purchase_path + "/save", json=payload).json()
            assert response.get("code") == 200, response
            assert current()["bookStock"] == product["bookStock"] + 1
            healthy()

            response = call(customer, "order/postOrder", payMethod="demo", addressId="demo-address",
                            clientRequestId="inventory_" + run_id,
                            orderList=[{"productId": str(pid), "buyCount": 1, "propertyValueIds": "default"}])
            assert response["code"] == 200, response
            order_id = response["data"]["orderId"]
            assert current()["reservedStock"] == product["reservedStock"] + 1
            assert current()["bookStock"] == product["bookStock"] + 1
            paid = call(customer, "order/sandboxPay", orderId=order_id)
            assert paid["code"] == 200 and paid["data"]["orderStatus"] == 1, paid
            sent = business(merchant, "POST", "/commerce/orders/" + order_id + "/ship", {})
            shipped = True
            again = business(merchant, "POST", "/commerce/orders/" + order_id + "/ship", {})
            assert sent["receiptId"] == again["receiptId"] and again["orderStatus"] == 2
            received = call(customer, "order/receiveOrder", orderId=order_id)
            assert received["code"] == 200 and received["data"]["orderStatus"] == 3, received

            warehouse_rows = business(merchant, "GET", f"/commerce/inventory/{pid}/warehouse-ledger")
            inbound = [row for row in warehouse_rows if row["receipt_id"] == receipt]
            outbound = [row for row in warehouse_rows if row["receipt_id"] == sent["receiptId"]]
            assert len(inbound) == 1 and int(inbound[0]["delta_quantity"]) == 1
            assert len(outbound) == 1 and int(outbound[0]["delta_quantity"]) == -1
            stock_rows = business(merchant, "GET", f"/commerce/inventory/{pid}/ledger")
            assert len([row for row in stock_rows if row["order_id"] == order_id and row["event_type"] == "DISPATCH"]) == 1
            assert any(row["reason"] == "ERP_SAVE:" + receipt for row in stock_rows)
            healthy()
            assert balances(business(merchant, "GET", "/commerce/inventory")) == before
            assert balances(business(platform, "GET", "/commerce/inventory")) == root_before
            assert sql(f"SELECT warehouse_id,SUM(plan_quantity) FROM ksdatabase_studio.inventory_product WHERE product_id={pid} GROUP BY warehouse_id ORDER BY warehouse_id") == warehouse_before
            report = {"runId": run_id, "tenantId": "studio", "purchaseReceipt": receipt,
                      "orderId": order_id, "dispatchReceipt": sent["receiptId"], "orderStatus": 3,
                      "purchaseAppliedOnce": True, "dispatchAppliedOnce": True, "warehouseLedgerBalanced": True,
                      "inventoryRestored": True, "otherTenantUnchanged": True, "reconciliationHealthy": True,
                      "paymentProvider": "LOCAL_SANDBOX", "logistics": "SIMULATED"}
            Path("../logs/inventory-flow-result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
            print("PASS live ERP -> C order -> sandbox payment -> dispatch -> receive", json.dumps(report))
        finally:
            # Only reverse our own test receipt when nothing was dispatched. Retain posted audit history.
            if posted and not shipped:
                can_reverse = order_id is None
                if order_id:
                    order = business(merchant, "GET", "/commerce/orders/" + order_id)
                    if order["orderStatus"] == 0:
                        assert call(customer, "order/cancelOrder", orderId=order_id)["code"] == 200
                        can_reverse = True
                if can_reverse:
                    response = merchant.post(purchase_path + "/delete", json=[{"systematicReceipt": receipt}]).json()
                    assert response.get("code") == 200, response


if __name__ == "__main__":
    run()
