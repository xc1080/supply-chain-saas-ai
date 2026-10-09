"""Create clearly labelled demo products through existing Java business APIs."""
import json
import os
from datetime import date
from decimal import Decimal
from urllib.request import Request, urlopen

BASE = os.getenv("FUSION_JAVA_URL", "http://127.0.0.1:8035").rstrip("/")
RECEIPT = "AI-DEMO-IN-20261007"
CATALOG = [
    ("DEMO-LAMP-ZB", "卧室柔光智能灯", "智能照明", "Zigbee/9W", "129", "70", 36, "演示商品；卧室柔光、可调亮度；Zigbee协议，需同协议网关"),
    ("DEMO-LAMP-WIFI", "WiFi智能台灯", "智能照明", "WiFi/12W", "199", "110", 18, "演示商品；卧室阅读、可调亮度；WiFi协议，无需Zigbee网关"),
    ("DEMO-LAMP-PRO", "客厅智能吸顶灯", "智能照明", "WiFi/36W", "499", "280", 8, "演示商品；客厅大面积照明；WiFi协议，不适合小预算方案"),
    ("DEMO-SENSOR-DOOR", "门窗开合传感器", "智能传感器", "Zigbee/电池", "69", "35", 50, "演示商品；检测门窗开合，联动灯光；Zigbee协议，需网关"),
    ("DEMO-SENSOR-MOTION", "人体感应传感器", "智能传感器", "Zigbee/电池", "89", "45", 24, "演示商品；有人自动开灯、走廊感应；Zigbee协议，需网关"),
    ("DEMO-GATEWAY-ZB", "Zigbee智能网关", "智能网关", "Zigbee/2.4G", "199", "100", 12, "演示商品；连接Zigbee灯具与传感器；不能保证跨品牌兼容"),
    ("DEMO-LOCK-WIFI", "WiFi指纹智能门锁", "智能安防", "WiFi/指纹", "999", "550", 5, "演示商品；指纹开锁、远程查看；购买前需确认门体安装尺寸"),
    ("DEMO-SWITCH-ZB", "Zigbee智能开关", "智能安防", "Zigbee/零火", "79", "40", 0, "演示商品；需要零火线和Zigbee网关；当前缺货，不可直接出库"),
]


def api(path, token=None, body=None, method=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(BASE + path, headers=headers,
                      data=json.dumps(body, ensure_ascii=False).encode() if body is not None else None,
                      method=method or ("POST" if body is not None else "GET"))
    with urlopen(request, timeout=20) as response:
        result = json.load(response)
    if result.get("code") != 200:
        raise RuntimeError(f"Java API {path}: code={result.get('code')} msg={result.get('msg')}")
    return result


def seed():
    token = api("/login", body={"username": os.getenv("FUSION_DEMO_USER", "admin"),
                                "password": os.getenv("FUSION_DEMO_PASSWORD", "admin123")})["token"]
    types = api("/baseDate/productType/list", token)["data"]
    root = next(t for t in types if str(t["parentId"]) == "0")
    categories = {}
    for category in dict.fromkeys(p[2] for p in CATALOG):
        category_name = "演示·" + category
        item = next((t for t in types if t["productTypeName"] == category_name), None)
        if item is None:
            api("/baseDate/productType/add", token, {"parentId": root["productTypeId"],
                "productTypeName": category_name, "orderNum": 1, "status": "0"})
            types = api("/baseDate/productType/list", token)["data"]
            item = next(t for t in types if t["productTypeName"] == category_name)
        categories[category] = str(item["productTypeId"])
    warehouses = api("/baseDate/warehouse/list?pageSize=100", token)["rows"]
    warehouse = next((w for w in warehouses if w["warehouseName"] == "智能家居演示仓"), None)
    if warehouse is None:
        api("/baseDate/warehouse/add", token, {"warehouseName": "智能家居演示仓", "warehouseValid": 0,
            "warehouseDirector": "演示管理员", "warehouseNotes": "融合 Demo 虚构库存"})
        warehouse = next(w for w in api("/baseDate/warehouse/list?pageSize=100", token)["rows"]
                         if w["warehouseName"] == "智能家居演示仓")
    warehouse_id = str(warehouse["warehouseId"])
    products = api("/baseDate/product/list?pageSize=500", token)["rows"]
    for code, name, category, spec, price, cost, quantity, note in CATALOG:
        if not any(p["productCode"] == code for p in products):
            api("/baseDate/product/add", token, {"productCode": code, "productName": name,
                "productType": categories[category], "productSpecifications": spec, "measureUnit": "件",
                "producer": "虚构演示", "costPrice": cost, "univalence": price, "discount": "100",
                "inventoryQty": "0", "defaultWarehouse": warehouse_id, "lowerLimit": "5",
                "upperLimit": "100", "status": "0", "notes": note})
    products = api("/baseDate/product/list?pageSize=500", token)["rows"]
    existing = api("/inventory/inventoryReceiptProcessing/" + RECEIPT, token).get("data")
    if existing and existing.get("receiptStatus") == 2:
        print("Demo receipt already approved; inventory not written again.")
    else:
        details = []
        for code, name, category, spec, price, cost, quantity, note in CATALOG:
            if quantity == 0:
                continue
            product = next(p for p in products if p["productCode"] == code)
            amount = str(Decimal(cost) * quantity)
            details.append({"systematicReceipt": RECEIPT, "productId": str(product["productId"]),
                "warehousingId": warehouse_id, "retrievalId": "0", "warehouseId": warehouse_id,
                "supplierId": "0", "customerId": "0", "productSpecifications": spec,
                "measureUnit": "件", "currentInventory": "0", "actualInventory": str(quantity),
                "planQuantity": str(quantity), "univalence": cost, "discount": "100", "money": amount,
                "cost": cost, "remarks": "融合 Demo 初始入库"})
        receipt = {"systematicReceipt": RECEIPT, "originalReceipt": "DEMO-INITIAL-STOCK",
            "receiptCategory": 3, "receiptType": 5, "receiptStatus": 1,
            "invoiceDate": date.today().isoformat(), "warehousingIds": warehouse_id, "retrievalIds": "0",
            "userIds": "1", "supplierIds": "0", "customerIds": "0", "deposit": "0",
            "totalAmount": str(sum(Decimal(d["money"]) for d in details)),
            "receiptNotes": "虚构智能家居商品演示库存", "details": details}
        if existing is None:
            api("/inventory/inventoryReceiptProcessing/save", token, receipt)
        receipt["receiptStatus"] = 2
        api("/inventory/inventoryReceiptProcessing/save", token, receipt)
    stock = api("/inventory/inventoryItemInquiry/list?pageSize=500", token)["rows"]
    quantities = {str(row["productId"]): Decimal(str(row["planQuantity"])) for row in stock
                  if str(row["warehouseId"]) == warehouse_id}
    dispatched = api("/commerce/orders?pageNum=1&pageSize=100", token)["data"]
    shipped_quantities = {}
    orders = dispatched["rows"]
    page_no = 1
    while len(orders) < dispatched["total"]:
        page_no += 1
        orders.extend(api(f"/commerce/orders?pageNum={page_no}&pageSize=100", token)["data"]["rows"])
    for order in orders:
        if order["orderStatus"] in (2, 3):
            for item in order["items"]:
                pid = str(item["productId"])
                shipped_quantities[pid] = shipped_quantities.get(pid, Decimal(0)) + Decimal(str(item["quantity"]))
    for code, name, category, spec, price, cost, quantity, note in CATALOG:
        product = next(p for p in products if p["productCode"] == code)
        actual = quantities.get(str(product["productId"]), Decimal(0))
        expected = Decimal(quantity) - shipped_quantities.get(str(product["productId"]), Decimal(0))
        if actual != expected or actual < 0:
            raise RuntimeError(f"Seed verification failed for {code}: expected={expected}, actual={actual}")
    print(f"Verified {len(CATALOG)} demo products, 1 approved inbound receipt, and real Java stock quantities.")


if __name__ == "__main__":
    seed()
