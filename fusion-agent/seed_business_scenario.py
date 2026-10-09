"""Import sourced sample models through ERP purchase and merchant listing APIs.

All suppliers, CNY prices, lead times and opening quantities are simulated. The
fixed approved purchase documents make reruns safe: no balances are reset and
no existing orders, products or journals are deleted.
"""
from __future__ import annotations

import argparse
from datetime import date
from decimal import Decimal
import json
import os
from pathlib import Path
from urllib.parse import urlparse

import httpx
from business_catalog import load_scenario

BASE = os.getenv("FUSION_JAVA_URL", "http://127.0.0.1:8035").rstrip("/")


def erp_spec(row):
    # Legacy ERP documents use a 16-character spec column. Full installation
    # and manufacturer specifications remain in the sourced technical profile.
    protocols = row["protocols"]
    if "thread" in protocols:
        return "Matter/Thread"
    if "sub-ghz" in protocols:
        return "WiFi/SubGHz/UK" if "wifi" in protocols else "SubGHz/UK"
    return "WiFi/E27/UK" if row["imageKind"] == "lamp" else "WiFi/UK"


def seed(tenant="demo", shop="default"):
    if urlparse(BASE).hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError("Sample data import is limited to the local learning deployment")
    scenario = load_scenario()
    if not scenario["products"]:
        raise ValueError("No sourced scenario products")
    username = os.getenv("FUSION_SEED_USER", "studio_admin" if tenant == "studio" else "admin")
    password = os.getenv("FUSION_SEED_PASSWORD", "admin123")
    with httpx.Client(base_url=BASE, timeout=30, trust_env=False) as client:
        def api(method, path, body=None):
            response = client.request(method, path, json=body)
            data = response.json()
            if data.get("code") != 200:
                raise RuntimeError(f"Scenario import {path}: code={data.get('code')}, msg={data.get('msg')}")
            response.raise_for_status()
            return data

        login = api("POST", "/login", {"username": username, "password": password})
        client.headers.update({"Authorization": "Bearer " + login["token"], "X-Shop-ID": shop})
        identity = api("GET", "/commerce/context")["data"]
        if identity["tenantId"] != tenant:
            raise ValueError("Authenticated tenant differs from requested scenario tenant")
        types = api("GET", "/baseDate/productType/list")["data"]
        root = next(row for row in types if str(row["parentId"]) == "0")
        categories = {}
        for name in dict.fromkeys(row["category"] for row in scenario["products"]):
            label = "样例·" + name
            category = next((row for row in types if row["productTypeName"] == label), None)
            if not category:
                api("POST", "/baseDate/productType/add", {"parentId": root["productTypeId"], "productTypeName": label, "orderNum": 2, "status": "0"})
                types = api("GET", "/baseDate/productType/list")["data"]
                category = next(row for row in types if row["productTypeName"] == label)
            categories[name] = str(category["productTypeId"])

        warehouses = api("GET", "/baseDate/warehouse/list?pageSize=500")["rows"]
        warehouse = next((row for row in warehouses if row["warehouseName"] == "样例·华东中心仓（虚构）"), None)
        if not warehouse:
            api("POST", "/baseDate/warehouse/add", {"warehouseName": "样例·华东中心仓（虚构）", "warehouseValid": 0,
                "warehouseDirector": "样例仓管", "warehouseNotes": "业务练习模拟仓；无真实实物"})
            warehouse = next(row for row in api("GET", "/baseDate/warehouse/list?pageSize=500")["rows"] if row["warehouseName"] == "样例·华东中心仓（虚构）")
        warehouse_id = str(warehouse["warehouseId"])

        vendors = api("GET", "/baseDate/supplier/list?pageSize=500")["rows"]
        supplier_ids = {}
        for row in scenario["suppliers"]:
            vendor = next((item for item in vendors if item["supplierCode"] == row["code"]), None)
            if not vendor:
                api("POST", "/baseDate/supplier/add", {"supplierCode": row["code"], "supplierName": row["name"],
                    "supplierOpeningDebt": "0", "status": 0, "remarks": "虚构业务样例；不属于厂商授权或真实供应关系"})
                vendors = api("GET", "/baseDate/supplier/list?pageSize=500")["rows"]
                vendor = next(item for item in vendors if item["supplierCode"] == row["code"])
            supplier_ids[row["code"]] = str(vendor["supplierId"])

        products = api("GET", "/baseDate/product/list?pageSize=500")["rows"]
        for row in scenario["products"]:
            if any(item["productCode"] == row["code"] for item in products):
                continue
            sim = row["simulation"]
            api("POST", "/baseDate/product/add", {"productCode": row["code"], "productName": row["name"],
                "productType": categories[row["category"]], "productSpecifications": erp_spec(row), "measureUnit": "件",
                "producer": row["brand"], "costPrice": str(sim["costPrice"]), "univalence": str(sim["salePrice"]),
                "discount": "100", "inventoryQty": "0", "defaultWarehouse": warehouse_id,
                "lowerLimit": str(sim["reorderPoint"]), "upperLimit": str(sim["targetStock"]), "status": "0",
                "notes": "业务样例；价格、库存与供应商模拟。海外版本，须核对地区和安装要求。"})
        products = api("GET", "/baseDate/product/list?pageSize=500")["rows"]
        by_code = {row["productCode"]: row for row in products}
        documents = []
        for vendor in scenario["suppliers"]:
            suffix = vendor["code"].removeprefix("LAB-SUP-")
            receipt_id = "LAB_INIT_" + suffix + "_20261008"
            path = "/purchase/purchaseReceiptProcessing"
            existing = api("GET", path + "/" + receipt_id).get("data")
            if existing and existing.get("receiptStatus") == 2:
                documents.append({"receiptId": receipt_id, "alreadyPosted": True})
                continue
            details = []
            for row in scenario["products"]:
                sim = row["simulation"]
                if sim["supplierCode"] != vendor["code"] or sim["openingStock"] == 0:
                    continue
                product = by_code[row["code"]]
                amount = Decimal(str(sim["costPrice"])) * sim["openingStock"]
                details.append({"productId": str(product["productId"]), "warehousingId": warehouse_id, "retrievalId": "0",
                    "supplierId": supplier_ids[vendor["code"]], "customerId": "0", "measureUnit": "件",
                    "productSpecifications": erp_spec(row), "planQuantity": str(sim["openingStock"]), "univalence": str(sim["costPrice"]),
                    "discount": "100", "money": str(amount), "cost": str(sim["costPrice"]),
                    "currentInventory": "0", "actualInventory": str(sim["openingStock"]), "remarks": "模拟期初采购入库"})
            payload = {"systematicReceipt": receipt_id, "originalReceipt": "LAB-SCENARIO-20261008", "receiptCategory": 1,
                "receiptType": 1, "receiptStatus": 2, "invoiceDate": date.today().isoformat(), "warehousingIds": warehouse_id,
                "retrievalIds": "0", "userIds": str(identity["userId"]), "supplierIds": supplier_ids[vendor["code"]],
                "customerIds": "0", "deposit": "0", "totalAmount": str(sum(Decimal(row["money"]) for row in details)),
                "receiptNotes": "样例期初采购：真实型号，虚构供应商与金额，无真实采购。", "details": details}
            api("POST", path + "/save", payload)
            documents.append({"receiptId": receipt_id, "alreadyPosted": False})

        for row in scenario["products"]:
            api("POST", "/commerce/products/" + str(by_code[row["code"]]["productId"]) + "/listing", {"listed": True})
        inventory = api("GET", "/commerce/inventory")["data"]
        scenario_codes = {row["code"] for row in scenario["products"]}
        published = [row for row in inventory if row["productCode"] in scenario_codes]
        if len(published) != len(scenario_codes):
            raise RuntimeError("Some scenario products were not published")
        reconciliation = api("GET", "/commerce/inventory/reconciliation")["data"]
        if not reconciliation["healthy"]:
            raise RuntimeError("Inventory reconciliation failed after sample import")
        report = {"tenantId": tenant, "shopId": shop, "products": len(published), "suppliers": len(supplier_ids),
                  "purchaseReceipts": documents, "reconciliationHealthy": True,
                  "stock": [{"code": row["productCode"], "productId": row["productId"], "onHand": row["onHand"], "available": row["availableStock"]} for row in published]}
        output = Path(__file__).parent.parent / "logs" / "business-scenario-seed-result.json"
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False))
        return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--tenant", choices=("demo", "studio"), default="demo")
    parser.add_argument("--shop", default="default")
    args = parser.parse_args()
    seed(args.tenant, args.shop)
