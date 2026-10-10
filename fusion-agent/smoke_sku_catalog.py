"""Owned fictional SKU family; exact selection, hold, sandbox pay and ERP dispatch.

Business APIs only. Existing SKUs untouched; every receipt/order is retained.
Manufacturer compatibility remains unverified.
"""
from contextlib import ExitStack
from datetime import date, datetime, timezone
import copy
import json
from pathlib import Path
from urllib.parse import urlparse
from smoke_supply_execution import Acceptance, JAVA, STORE, PURCHASE, SafeFailure


class SkuCatalog(Acceptance):
    def run(self):
        for base in (JAVA, STORE):
            if urlparse(base).hostname not in {"127.0.0.1", "localhost", "::1"}:
                raise SafeFailure("SKU verification is limited to local services")
        self.report.update(kind="OWNED_SKU_CATALOG_CLOSURE", existingSkuChanged=False)
        with ExitStack() as stack:
            admin = self.merchant(stack, "admin")
            foreign = self.merchant(stack, "studio_admin")
            reviewer = self.merchant(stack, "demo_supply_reviewer")
            buyer = self.visitor(stack)
            categories = self.api(admin, "categories", "GET", "/baseDate/product/productTypeTree?status=0")
            def leaves(rows):
                for row in rows:
                    if row.get("children"): yield from leaves(row["children"])
                    elif int(row.get("id", 0)) > 0: yield int(row["id"])
            category = next(leaves(categories))
            warehouses = self.request(admin, "warehouses", "GET", "/baseDate/warehouse/list?pageSize=500")["rows"]
            wid = str(next(row for row in warehouses if int(row.get("warehouseValid", 0)) == 0)["warehouseId"])
            def product(suffix, price, spec):
                code = "LAB-SKU-" + self.run_id.upper() + "-" + suffix
                body = {"productCode": code, "productName": "学习智能台灯 " + self.run_id,
                        "productType": str(category), "productSpecifications": spec, "measureUnit": "件",
                        "status": "0", "costPrice": "18", "univalence": str(price), "discount": "1",
                        "upperLimit": "100", "lowerLimit": "0", "defaultWarehouse": wid,
                        "notes": "虚构SKU学习数据；不代表厂商设备、真实报价或兼容认证"}
                self.request(admin, suffix + ".create", "POST", "/baseDate/product/add", body=body)
                rows = self.request(admin, suffix + ".find", "GET", "/baseDate/product/list?productCode=" + code)["rows"]
                self.check(suffix + ".singleSku", len(rows) == 1)
                pid = int(rows[0]["productId"])
                self.api(admin, suffix + ".shopBinding", "POST", f"/commerce/products/{pid}/listing", {"listed": False})
                return pid, body
            white, white_product = product("WHITE", 99, "白色/WiFi/12W")
            black, black_product = product("BLACK", 129, "黑色/Zigbee/12W")
            self.report["state"].update(whiteProductId=white, blackProductId=black)
            properties = [{"propertyId": "color", "propertyName": "颜色", "propertyValues": [
                {"propertyValueId": "white", "propertyValue": "白色"}, {"propertyValueId": "black", "propertyValue": "黑色"}]},
                {"propertyId": "protocol", "propertyName": "协议", "propertyValues": [
                    {"propertyValueId": "wifi", "propertyValue": "WiFi"}, {"propertyValueId": "zigbee", "propertyValue": "Zigbee"}]}]
            request = {"name": "学习智能台灯 · " + self.run_id, "properties": properties,
                       "skus": [{"productId": white, "attributes": {"color": "white", "protocol": "wifi"}},
                                {"productId": black, "attributes": {"color": "black", "protocol": "zigbee"}}]}
            self.api(reviewer, "readRoleCannotCreateFamily", "POST", "/commerce/spus", request, 403)
            family = self.api(admin, "createFamily", "POST", "/commerce/spus", request)
            fid = family["spuId"]
            self.report["state"]["spuId"] = fid
            self.api(foreign, "foreignTenantCannotAppend", "POST", f"/commerce/spus/{fid}/skus", {"skus": request["skus"]}, 404)
            self.check("foreignListExcludesFamily", fid not in {row["spuId"] for row in self.api(foreign, "foreignList", "GET", "/commerce/spus")})
            self.api(admin, "appendReplay", "POST", f"/commerce/spus/{fid}/skus", {"skus": request["skus"]})
            changed = {"skus": [{"productId": black, "attributes": {"color": "white", "protocol": "zigbee"}}]}
            self.api(admin, "cannotRebindSku", "POST", f"/commerce/spus/{fid}/skus", changed, 409)
            before = len(self.api(admin, "familiesBeforeFailedCreate", "GET", "/commerce/spus"))
            self.api(admin, "boundSkuCannotMoveFamily", "POST", "/commerce/spus", request, 409)
            self.check("failedCreateRollsBackFamily", len(self.api(admin, "familiesAfterFailedCreate", "GET", "/commerce/spus")) == before)
            changed_product = {**black_product, "productId": str(black), "productSpecifications": "白色/WiFi/12W"}
            self.request(admin, "legacyEditCannotChangeIdentity", "PUT", "/baseDate/product/update", body=changed_product, expected=409)
            self.request(admin, "boundSkuCannotDelete", "DELETE", f"/baseDate/product/{black}", expected=409)
            for pid, quantity, spec, suffix in [(white, 6, white_product["productSpecifications"], "WHITE"),
                                               (black, 3, black_product["productSpecifications"], "BLACK")]:
                rid = "SKU_" + suffix + "_" + self.run_id
                receipt = {"systematicReceipt": rid, "originalReceipt": "SKU-" + self.run_id,
                    "receiptCategory": 1, "receiptType": 1, "receiptStatus": 2, "invoiceDate": date.today().isoformat(),
                    "warehousingIds": wid, "retrievalIds": "0", "userIds": "1", "supplierIds": "0", "customerIds": "0",
                    "deposit": "0", "totalAmount": str(quantity * 18), "receiptNotes": "虚构规格入库，保留原始凭证",
                    "details": [{"productId": str(pid), "warehousingId": wid, "retrievalId": "0", "supplierId": "0",
                        "customerId": "0", "measureUnit": "件", "productSpecifications": spec, "planQuantity": str(quantity),
                        "univalence": "18", "discount": "1", "money": str(quantity * 18), "cost": "18", "remarks": "学习SKU"}]}
                self.request(admin, suffix + ".purchaseReceipt", "POST", PURCHASE + "/save", body=receipt)
                self.api(admin, suffix + ".listing", "POST", f"/commerce/products/{pid}/listing", {"listed": True})
            detail = self.call(buyer, "customerSkuDetail", "product/getProduct", productId=str(white))
            self.check("familyContainsOnlyTwoConcreteVariants", {int(row["skuId"]) for row in detail["skuList"]} == {white, black})
            self.check("pricesAndStocksIndependent", sorted((row["price"], row["stock"]) for row in detail["skuList"]) == [(99, 6), (129, 3)])
            self.check("compatibilityNotInvented", all(row.get("technicalProfile") is None for row in detail["skuList"]))
            self.call(buyer, "wrongSkuCartRejected", "productCart/add2Cart", expected=422, productId=str(black), buyCount=1, propertyValueIds="white-wifi")
            self.call(buyer, "exactSkuCart", "productCart/add2Cart", productId=str(black), buyCount=1, propertyValueIds="black-zigbee")
            cart = self.call(buyer, "readExactSkuCart", "productCart/loadCart")["list"]
            self.check("cartPreservesBlackSku", len(cart) == 1 and int(cart[0]["productId"]) == black and cart[0]["price"] == 129 and cart[0]["propertyValueIds"] == "black-zigbee")
            address = self.call(buyer, "ownedAddress", "userAddress/addAddress", addressee="SKU学习访客", phone="00000000000",
                                address="虚构地址：SKU规格学习验收", defaultType=1)["addressId"]
            order_args = {"payMethod": "demo", "addressId": address, "clientRequestId": "sku_" + self.run_id,
                          "orderList": [{"productId": str(black), "buyCount": 1, "propertyValueIds": "black-zigbee", "price": .01}]}
            wrong_order = copy.deepcopy(order_args)
            wrong_order["clientRequestId"] += "_wrong"
            wrong_order["orderList"][0]["propertyValueIds"] = "white-wifi"
            self.call(buyer, "wrongOrderComboRejectedByAuthority", "order/postOrder", expected=409, **wrong_order)
            oid = self.call(buyer, "placeBlackSkuOrder", "order/postOrder", **order_args)["orderId"]
            self.report["state"]["orderId"] = oid
            self.check("idempotentReplay", self.call(buyer, "replayBlackSkuOrder", "order/postOrder", **order_args)["orderId"] == oid)
            purchased = self.call(buyer, "orderSnapshot", "order/getMyOrderDetail", orderId=oid)
            self.check("priceAuthorityAndSnapshot", purchased["amount"] == 129 and purchased["orderItemList"][0]["skuSnapshot"]["attributes"] == {"color": "black", "protocol": "zigbee"})
            self.check("whiteSkuUnchangedByBlackOrder", self.stock(admin, white, "whiteAfterHold")["availableStock"] == 6)
            self.check("onlyBlackHeld", self.stock(admin, black, "blackAfterHold")["reservedStock"] == 1)
            self.call(buyer, "sandboxPay", "order/sandboxPay", orderId=oid, paymentRequestId="sku_pay_" + self.run_id, scenario="success")
            ship = {"requestKey": "sku_ship_" + self.run_id, "items": [{"productId": black, "quantity": 1}],
                    "carrier": "Simulated SKU carrier", "trackingNo": "SKU-" + self.run_id}
            self.api(admin, "merchantDispatch", "POST", f"/commerce/orders/{oid}/ship", ship)
            self.api(admin, "dispatchReplay", "POST", f"/commerce/orders/{oid}/ship", ship)
            self.call(buyer, "receive", "order/receiveOrder", orderId=oid)
            self.check("blackStockDecrementsOnce", self.stock(admin, black, "blackFinal")["bookStock"] == 2)
            self.check("whiteStockStillSix", self.stock(admin, white, "whiteFinal")["bookStock"] == 6)
            self.check("stockReconciliationHealthy", self.api(admin, "reconcile", "GET", "/commerce/inventory/reconciliation")["healthy"])
            self.report.update(passed=True, finishedAt=datetime.now(timezone.utc).isoformat(),
                               customerUrl=f"http://127.0.0.1:6001/product/{white}")


if __name__ == "__main__":
    check = SkuCatalog()
    try:
        check.run()
    finally:
        target = Path(__file__).resolve().parent.parent / "logs/sku-catalog-result.json"
        target.write_text(json.dumps(check.report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"passed": check.report.get("passed", False), "checks": len(check.report["checks"]),
                      "state": check.report["state"], "customerUrl": check.report.get("customerUrl")}, ensure_ascii=False))
