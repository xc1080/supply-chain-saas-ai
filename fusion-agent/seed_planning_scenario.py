"""Idempotent local merchant samples. No stock resets and no real procurement."""
from contextlib import ExitStack
from datetime import datetime, timedelta
import json
from pathlib import Path
from business_catalog import load_scenario
from smoke_business_lifecycle import merchant, api


def run():
    with ExitStack() as stack:
        shop = merchant(stack, "admin")
        assert api(shop, "GET", "/commerce/context")["tenantId"] == "demo"
        products = {p["productCode"]: p for p in api(shop, "GET", "/commerce/inventory")}
        warehouses = shop.get("/baseDate/warehouse/list", params={"pageSize": 500}).json()["rows"]
        warehouse = next(w for w in warehouses if w["warehouseName"] == "样例·华东中心仓（虚构）")
        wid = warehouse["warehouseId"]
        api(shop, "POST", "/commerce/planning/policy", {"dailyItemCapacity": 200, "dispatchDays": 1})
        for p in load_scenario()["products"]:
            api(shop, "POST", "/commerce/planning/policy", {
                "productId": products[p["code"]]["productId"], "supplierLeadDays": p["simulation"]["supplierLeadDays"]})
        events = []
        for key, state, quantity in [("LAB-CONDITION-T100-Q-20261009", "QUALITY_HOLD", 2), ("LAB-CONDITION-T100-D-20261009", "DAMAGED", 1)]:
            events.append(api(shop, "POST", "/commerce/planning/conditions", {"requestKey": key,
                "productId": products["LAB-TAPO-T100"]["productId"], "warehouseId": wid,
                "from": "SELLABLE", "to": state, "quantity": quantity,
                "reason": "虚构仓库练习：待检与破损分开登记，不发生实物出入库"}))
        existing = {r["sourceReference"]: r for r in api(shop, "GET", "/commerce/planning/incoming")}
        batches = []
        for code, quantity, days, label in [("LAB-TAPO-T310", 12, 4, "LAB-SUPPLY-T310-20261009"),
                                              ("LAB-TAPO-T300", 10, -1, "LAB-OVERDUE-T300-20261009")]:
            # Preserve the original expected date on reruns; it is evidence, not a sliding deadline.
            if label in existing:
                batches.append(existing[label]); continue
            batches.append(api(shop, "POST", "/commerce/planning/incoming", {
                "requestKey": label, "productId": products[code]["productId"], "warehouseId": wid,
                "sourceReference": label, "quantity": quantity,
                "expectedAt": (datetime.now() + timedelta(days=days)).replace(microsecond=0).isoformat()}))
        result = {"dataKind": "SIMULATED", "tenantId": "demo", "warehouseId": wid,
                  "conditions": events, "incoming": batches,
                  "replenishment": api(shop, "GET", "/commerce/planning/replenishment"),
                  "reconciliation": api(shop, "GET", "/commerce/inventory/reconciliation")}
        assert result["reconciliation"]["healthy"], result["reconciliation"]
        output = Path(__file__).parent.parent / "logs" / "planning-scenario-seed-result.json"
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"conditions": len(events), "incoming": len(batches), "healthy": True}))
        return result


if __name__ == "__main__":
    run()
