"""Live durable checkout/load verification. Changes only this run's Studio activity and orders.

Acceptance is a queue receipt. A winner exists only when the worker reports SUCCEEDED.
No payment, dispatch, stock reset, customer-order deletion or service kill is performed.
"""
from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
import json
import math
from pathlib import Path
import time
import uuid

import httpx

from bootstrap_tenants import sql
from smoke_saas import admin, business, store, call


def percentile(values, fraction):
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(len(ordered) * fraction) - 1)], 2) if ordered else None


def balances(rows):
    fields = ("bookStock", "onHand", "reservedStock", "activityStock", "availableStock")
    return {str(row["productId"]): {key: row[key] for key in fields if key in row} for row in rows}


def terminal_code(receipt):
    return 200 if receipt["state"] == "SUCCEEDED" else int(receipt.get("errorCode") or 500)


def run(requests=200, concurrency=50):
    run_id = uuid.uuid4().hex[:12]
    activity_id = "async_" + run_id
    result_path = Path(__file__).resolve().parent.parent / "logs" / "async-checkout-load-result.json"
    clients, created_orders, jobs = [], {}, {}
    a = b = foreign = None
    foreign_shop = None
    before_studio = before_demo = None
    activity_created = False
    capacity = 0
    started = time.perf_counter()
    report = {"runId": run_id, "requests": requests, "concurrency": concurrency,
              "acceptanceP50Ms": None, "acceptanceP95Ms": None, "completionP95Ms": None,
              "elapsedSeconds": None, "acceptanceCodes": {}, "terminalCodes": {},
              "activityCapacity": None, "accepted": 0, "succeeded": 0,
              "oversold": None, "ledgerBalanced": False, "inventoryRestored": False,
              "isolationVerified": False}

    def expire_own_activity():
        if activity_created:
            sql("UPDATE ksdatabase_studio.commerce_activity SET ends_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) "
                f"WHERE activity_id='{activity_id}' AND shop_id='default'")
            # Reads are now pure: wait for the normal scheduler to post the expiry event.
            deadline = time.monotonic() + 10
            while int(sql(f"SELECT COUNT(*) FROM ksdatabase_studio.commerce_stock_ledger WHERE event_key='ACTIVITY_EXPIRE:{activity_id}:{pid}'")) == 0:
                assert time.monotonic() < deadline, "Activity expiry scheduler did not release this run's allocation"
                time.sleep(.2)

    def cancel_own_orders():
        if b is None or not activity_created:
            return
        rows = sql("SELECT order_id,owner_id FROM ksdatabase_studio.commerce_order "
                   f"WHERE activity_id='{activity_id}' AND shop_id='default' AND status=0")
        for row in rows.splitlines():
            order_id, owner = row.split("\t")
            business(b, "POST", "/commerce/orders/" + order_id + "/cancel", {"ownerId": owner})

    try:
        a, b = admin("admin"), admin("studio_admin")
        a.headers["X-Shop-ID"] = b.headers["X-Shop-ID"] = "default"
        assert business(a, "GET", "/commerce/context")["tenantId"] == "demo"
        assert business(b, "GET", "/commerce/context")["tenantId"] == "studio"
        before_demo = balances(business(a, "GET", "/commerce/inventory"))
        studio_inventory = business(b, "GET", "/commerce/inventory")
        before_studio = balances(studio_inventory)
        healthy = business(b, "GET", "/commerce/inventory/reconciliation")
        assert healthy["healthy"], healthy
        available = [p for p in studio_inventory if p.get("listed", 1) in (1, True, "1") and int(p["availableStock"]) > 0]
        product = max(available, key=lambda p: (p["productCode"] == "DEMO-LAMP-ZB", int(p["availableStock"])))
        pid = int(product["productId"])
        capacity = min(12, requests, int(product["availableStock"]))
        assert capacity > 0, "A listed Studio product with available stock is required"
        report["activityCapacity"] = capacity
        baseline_ledger_max = int(sql(f"SELECT COALESCE(MAX(ledger_id),0) FROM ksdatabase_studio.commerce_stock_ledger WHERE product_id={pid}"))
        historical_query = f"SELECT * FROM ksdatabase_studio.commerce_stock_ledger WHERE product_id={pid} AND ledger_id<={baseline_ledger_max} ORDER BY ledger_id"
        historical_ledger = sql(historical_query)
        now = datetime.now()
        business(b, "POST", "/commerce/activities", {
            "activityId": activity_id, "productId": pid, "title": "Async verification " + run_id,
            "capacity": capacity, "perOwnerLimit": 1, "price": 99,
            "startsAt": (now - timedelta(seconds=5)).isoformat(timespec="seconds"),
            "endsAt": (now + timedelta(minutes=10)).isoformat(timespec="seconds")})
        activity_created = True
        assert not any(row["activityId"] == activity_id for row in business(a, "GET", "/commerce/activities"))
        clients = [store() for _ in range(requests)]

        def submit(index):
            start = time.perf_counter()
            result = call(clients[index], "seckill/submitOrder", activityId=activity_id,
                          requestKey=run_id + "_" + str(index), ownerId="forged", tenantId="demo", shopId="forged-shop")
            return index, result, (time.perf_counter() - start) * 1000, start

        load_started = time.perf_counter()
        with ThreadPoolExecutor(max_workers=concurrency) as pool:
            submissions = list(pool.map(submit, range(requests)))
        acceptance_codes = Counter(result["code"] for _, result, _, _ in submissions)
        assert set(acceptance_codes) <= {200, 409, 429}, acceptance_codes
        for index, result, _, request_start in submissions:
            if result["code"] == 200:
                receipt = result["data"]
                assert receipt["state"] in {"PENDING", "PROCESSING", "SUCCEEDED", "REJECTED"}, receipt
                jobs[index] = {"receipt": receipt, "start": request_start}
        report.update(acceptanceCodes=dict(acceptance_codes), accepted=len(jobs),
                      acceptanceP50Ms=percentile([duration for _, _, duration, _ in submissions], .5),
                      acceptanceP95Ms=percentile([duration for _, _, duration, _ in submissions], .95))
        assert len(jobs) >= capacity, (len(jobs), capacity, acceptance_codes)

        def complete(index):
            deadline = time.monotonic() + 120
            receipt = jobs[index]["receipt"]
            while receipt["state"] not in {"SUCCEEDED", "REJECTED"}:
                assert time.monotonic() < deadline, ("Queue did not finish", receipt)
                result = clients[index].get("/api/seckill/getCheckoutStatus", params={"jobId": receipt["jobId"]}).json()
                assert result["code"] == 200, result
                receipt = result["data"]
                if receipt["state"] not in {"SUCCEEDED", "REJECTED"}: time.sleep(.2)
            return index, receipt, (time.perf_counter() - jobs[index]["start"]) * 1000

        with ThreadPoolExecutor(max_workers=concurrency) as pool:
            completed = list(pool.map(complete, jobs))
        terminal_codes = Counter(terminal_code(receipt) for _, receipt, _ in completed)
        winners = [(index, receipt) for index, receipt, _ in completed if receipt["state"] == "SUCCEEDED"]
        created_orders.update((receipt["orderId"], index) for index, receipt in winners)
        report.update(terminalCodes=dict(terminal_codes), succeeded=len(winners),
                      completionP95Ms=percentile([duration for _, _, duration in completed], .95),
                      loadElapsedSeconds=round(time.perf_counter() - load_started, 3),
                      oversold=len(winners) > capacity)
        assert len(winners) == capacity, (terminal_codes, capacity, report)
        assert len(created_orders) == capacity, "Duplicate order IDs among checkout winners"
        assert set(terminal_codes) <= {200, 409}, terminal_codes

        inventory = next(p for p in business(b, "GET", "/commerce/inventory") if int(p["productId"]) == pid)
        baseline = before_studio[str(pid)]
        assert inventory["bookStock"] == baseline["bookStock"]
        assert inventory["reservedStock"] == baseline["reservedStock"] + capacity
        assert inventory["activityStock"] == baseline["activityStock"]
        for index, receipt in winners:
            order = business(b, "GET", "/commerce/orders/" + receipt["orderId"])
            assert order["tenantId"] == "studio" and order["shopId"] == "default" and float(order["totalAmount"]) == 99
        first_index, first = winners[0]
        # Retry under admission pressure with the SAME request key, never manufacture another request.
        repeat_deadline = time.monotonic() + 5
        while True:
            repeated = call(clients[first_index], "seckill/submitOrder", activityId=activity_id,
                            requestKey=run_id + "_" + str(first_index))
            if repeated["code"] != 429 or time.monotonic() >= repeat_deadline: break
            time.sleep(.25)
        assert repeated["code"] == 200 and repeated["data"]["jobId"] == first["jobId"]
        assert repeated["data"]["orderId"] == first["orderId"]

        foreign = store()
        assert foreign.get("/api/seckill/getCheckoutStatus", params={"jobId": first["jobId"]}).json()["code"] == 404
        session = clients[first_index].cookies.get("simlect_studio_session")
        from commerce_api import owner_id
        owner = owner_id(session)
        assert a.get("/commerce/checkouts/" + first["jobId"], params={"ownerId": owner}).json()["code"] == 404
        foreign_shop = business(b, "POST", "/commerce/shops", {"shopName": "Isolation verification " + run_id})["shopId"]
        assert b.get("/commerce/checkouts/" + first["jobId"], params={"ownerId": owner}, headers={"X-Shop-ID": foreign_shop}).json()["code"] == 404
        assert b.get("/commerce/orders/" + first["orderId"], headers={"X-Shop-ID": foreign_shop}).json()["code"] == 404
        report["isolationVerified"] = True

        entries = [e for e in business(b, "GET", f"/commerce/inventory/{pid}/ledger?limit=200") if e.get("activity_id") == activity_id]
        assert sum(int(e["delta_on_hand"]) for e in entries) == 0
        assert sum(int(e["delta_reserved"]) for e in entries) == capacity
        assert sum(int(e["delta_activity"]) for e in entries) == 0
        assert sum(e["event_type"] == "RESERVE" for e in entries) == capacity
        assert len({e["event_key"] for e in entries}) == len(entries)
        assert sql(historical_query) == historical_ledger, "Historical stock ledger was modified"
        assert business(b, "GET", "/commerce/inventory/reconciliation")["healthy"]

        # Cancel only our unpaid winners; ending only our activity releases its returned allocation.
        cancel_own_orders()
        expire_own_activity()
        after = balances(business(b, "GET", "/commerce/inventory"))
        assert after == before_studio, (before_studio, after)
        assert balances(business(a, "GET", "/commerce/inventory")) == before_demo
        assert business(b, "GET", "/commerce/inventory/reconciliation")["healthy"]
        finished = [e for e in business(b, "GET", f"/commerce/inventory/{pid}/ledger?limit=200") if e.get("activity_id") == activity_id]
        assert all(sum(int(e[field]) for e in finished) == 0 for field in ("delta_on_hand", "delta_reserved", "delta_activity"))
        assert sql(historical_query) == historical_ledger
        report.update(ledgerBalanced=True, inventoryRestored=True)
        print("PASS durable acceptance, bounded concurrency, exact capacity, idempotency, owner/tenant/shop isolation and immutable balanced ledger")
        return report
    except Exception as failure:
        report["failure"] = type(failure).__name__ + ": " + str(failure)[:1200]
        raise
    finally:
        try:
            expire_own_activity()
            cancel_own_orders()
            if b is not None and activity_created:
                # Confirm the committed snapshot after the scheduled expiry; reads never release stock.
                business(b, "GET", "/commerce/inventory")
            if foreign_shop:
                sql(f"UPDATE ksdatabase_studio.commerce_shop SET status='DISABLED' WHERE shop_id='{foreign_shop}'")
        finally:
            report["elapsedSeconds"] = round(time.perf_counter() - started, 3)
            result_path.parent.mkdir(parents=True, exist_ok=True)
            result_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
            print(json.dumps(report, ensure_ascii=False))
            for client in [*clients, foreign, a, b]:
                if client is not None: client.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Verify the real local async checkout chain without payment or dispatch")
    parser.add_argument("--requests", type=int, default=200)
    parser.add_argument("--concurrency", type=int, default=50)
    args = parser.parse_args()
    if not 12 <= args.requests <= 500 or not 1 <= args.concurrency <= 100:
        parser.error("requests must be 12..500 and concurrency must be 1..100")
    run(args.requests, args.concurrency)
