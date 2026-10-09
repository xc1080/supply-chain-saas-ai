"""Simulate abandoned SQL checkout leases; never stop a process or alter another run's records."""
from __future__ import annotations

from datetime import datetime, timedelta
from hashlib import sha256
import json
from pathlib import Path
import time
import uuid

from bootstrap_tenants import sql
from commerce_api import owner_id
from smoke_async_checkout import balances
from smoke_saas import admin, business, store, call


def run():
    run_id = uuid.uuid4().hex[:12]
    activity = "recovery_" + run_id
    job = uuid.uuid4().hex
    key = "recovery_" + run_id
    lease = uuid.uuid4().hex
    fingerprint = sha256(("seckill:" + activity).encode()).hexdigest()
    report = {"runId": run_id, "scenario": "simulated_database_crash_state",
              "expiredLeaseRecovered": False, "committedOrderRecovered": False,
              "duplicateReservation": None, "duplicateLedger": None,
              "ownerIsolation": False, "timeoutReleased": False,
              "historicalLedgerUnchanged": False, "ledgerBalanced": False, "inventoryRestored": False}
    client = foreign = a = b = None
    order_id = None
    created = False
    started = time.perf_counter()
    result_path = Path(__file__).resolve().parent.parent / "logs" / "checkout-recovery-result.json"

    def status():
        body = client.get("/api/seckill/getCheckoutStatus", params={"jobId": job}).json()
        assert body["code"] == 200, body
        return body["data"]

    def wait_terminal():
        deadline = time.monotonic() + 30
        while True:
            receipt = status()
            if receipt["state"] in {"SUCCEEDED", "REJECTED"}: return receipt
            assert time.monotonic() < deadline, receipt
            time.sleep(.2)

    def expire_activity():
        if created:
            sql(f"UPDATE ksdatabase_studio.commerce_activity SET ends_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE activity_id='{activity}' AND shop_id='default'")
            deadline = time.monotonic() + 10
            while int(sql(f"SELECT COUNT(*) FROM ksdatabase_studio.commerce_stock_ledger WHERE event_key='ACTIVITY_EXPIRE:{activity}:{pid}'")) == 0:
                assert time.monotonic() < deadline, "Activity expiry scheduler did not release this run's allocation"
                time.sleep(.2)

    try:
        a, b, client, foreign = admin("admin"), admin("studio_admin"), store(), store()
        a.headers["X-Shop-ID"] = b.headers["X-Shop-ID"] = "default"
        assert business(b, "GET", "/commerce/context")["tenantId"] == "studio"
        before_demo = balances(business(a, "GET", "/commerce/inventory"))
        inventory = business(b, "GET", "/commerce/inventory")
        before = balances(inventory)
        assert business(b, "GET", "/commerce/inventory/reconciliation")["healthy"]
        product = next(p for p in inventory if p.get("listed", 1) in (True, 1, "1") and int(p["availableStock"]) >= 1)
        pid = int(product["productId"])
        baseline = before[str(pid)]
        max_ledger = int(sql(f"SELECT COALESCE(MAX(ledger_id),0) FROM ksdatabase_studio.commerce_stock_ledger WHERE product_id={pid}"))
        historical_query = f"SELECT * FROM ksdatabase_studio.commerce_stock_ledger WHERE product_id={pid} AND ledger_id<={max_ledger} ORDER BY ledger_id"
        historical = sql(historical_query)
        owner = owner_id(client.cookies.get("simlect_studio_session"))
        address = next(row for row in call(client,"userAddress/loadDataList")["data"] if row["addressId"] == client._smoke_address_id)
        snapshot = json.dumps({name:address[name] for name in ("addressee","phone","address")},ensure_ascii=False,separators=(",",":"))
        fingerprint = sha256(("seckill:"+activity+":"+snapshot).encode()).hexdigest()
        shipping_sql = "CONVERT(UNHEX('"+snapshot.encode().hex()+"') USING utf8mb4)"
        now = datetime.now()
        business(b, "POST", "/commerce/activities", {"activityId": activity, "productId": pid,
                 "title": "Lease recovery verification " + run_id, "capacity": 1, "perOwnerLimit": 1,
                 "price": 99, "startsAt": (now-timedelta(seconds=5)).isoformat(timespec="seconds"),
                 "endsAt": (now+timedelta(minutes=10)).isoformat(timespec="seconds")})
        created = True
        pending_before = int(sql("SELECT pending_count FROM ksdatabase_studio.commerce_checkout_gate WHERE gate_id=1"))
        assert pending_before < 999
        # One atomic fault fixture: accepted outbox + inbox + capacity, worker disappeared after claiming.
        sql("START TRANSACTION; SELECT pending_count FROM ksdatabase_studio.commerce_checkout_gate WHERE gate_id=1 FOR UPDATE; "
            "INSERT INTO ksdatabase_studio.commerce_checkout_job(job_id,shop_id,activity_id,owner_id,request_key,fingerprint,shipping_address,state,attempts,next_attempt_at,lease_until,lease_token,created_at,updated_at) "
            f"VALUES ('{job}','default','{activity}','{owner}','{key}','{fingerprint}',{shipping_sql},'PROCESSING',1,TIMESTAMPADD(SECOND,-5,CURRENT_TIMESTAMP),TIMESTAMPADD(SECOND,-2,CURRENT_TIMESTAMP),'{lease}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP); "
            "INSERT INTO ksdatabase_studio.commerce_checkout_outbox(event_id,job_id,event_type,state,created_at) "
            f"VALUES ('{uuid.uuid4().hex}','{job}','CHECKOUT_REQUESTED','PENDING',CURRENT_TIMESTAMP); "
            "UPDATE ksdatabase_studio.commerce_checkout_gate SET pending_count=pending_count+1 WHERE gate_id=1; COMMIT")
        first = wait_terminal()
        assert first["state"] == "SUCCEEDED" and int(first["attempts"]) >= 2, first
        order_id = first["orderId"]
        report["expiredLeaseRecovered"] = True
        held = next(p for p in business(b, "GET", "/commerce/inventory") if int(p["productId"]) == pid)
        assert held["reservedStock"] == baseline["reservedStock"] + 1
        assert held["bookStock"] == baseline["bookStock"]
        order_ledger_query = f"SELECT * FROM ksdatabase_studio.commerce_stock_ledger WHERE order_id='{order_id}' ORDER BY ledger_id"
        first_order_ledger = sql(order_ledger_query)
        assert int(sql(f"SELECT COUNT(*) FROM ksdatabase_studio.commerce_stock_ledger WHERE order_id='{order_id}' AND event_type='RESERVE'")) == 1
        held_balances = balances(business(b, "GET", "/commerce/inventory"))
        assert foreign.get("/api/seckill/getCheckoutStatus", params={"jobId": job}).json()["code"] == 404
        assert a.get("/commerce/checkouts/" + job, params={"ownerId": owner}).json()["code"] == 404
        report["ownerIsolation"] = True

        # The order committed but the worker disappeared before its inbox/outbox acknowledgement.
        # Reopen only our successful fixture, atomically restoring its one outstanding capacity slot.
        sql("START TRANSACTION; SELECT pending_count FROM ksdatabase_studio.commerce_checkout_gate WHERE gate_id=1 FOR UPDATE; "
            f"UPDATE ksdatabase_studio.commerce_checkout_job SET state='PROCESSING',result_order_id=NULL,lease_token='{uuid.uuid4().hex}',lease_until=TIMESTAMPADD(SECOND,-2,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE job_id='{job}' AND owner_id='{owner}' AND activity_id='{activity}' AND state='SUCCEEDED'; "
            f"UPDATE ksdatabase_studio.commerce_checkout_outbox SET state='PENDING',completed_at=NULL WHERE job_id='{job}'; "
            "UPDATE ksdatabase_studio.commerce_checkout_gate SET pending_count=pending_count+1 WHERE gate_id=1; COMMIT")
        second = wait_terminal()
        assert second["state"] == "SUCCEEDED" and second["orderId"] == order_id, second
        assert int(second["attempts"]) > int(first["attempts"])
        assert sql(order_ledger_query) == first_order_ledger, "Recovered committed order reserved inventory twice"
        assert balances(business(b, "GET", "/commerce/inventory")) == held_balances
        assert int(sql(f"SELECT COUNT(*) FROM ksdatabase_studio.commerce_order WHERE activity_id='{activity}'")) == 1
        assert sql(f"SELECT state FROM ksdatabase_studio.commerce_checkout_outbox WHERE job_id='{job}'") == "DONE"
        assert int(sql("SELECT pending_count FROM ksdatabase_studio.commerce_checkout_gate WHERE gate_id=1")) == pending_before
        report.update(committedOrderRecovered=True, duplicateReservation=False, duplicateLedger=False)
        assert business(b, "GET", "/commerce/inventory/reconciliation")["healthy"]

        # Expire only our unpaid order; the running normal scheduler must release its hold once.
        sql(f"UPDATE ksdatabase_studio.commerce_order SET expires_at=TIMESTAMPADD(SECOND,-2,CURRENT_TIMESTAMP) WHERE order_id='{order_id}' AND owner_id='{owner}' AND activity_id='{activity}' AND status=0")
        deadline = time.monotonic() + 12
        while True:
            order = business(b, "GET", "/commerce/orders/" + order_id)
            if order["orderStatus"] == 4: break
            assert time.monotonic() < deadline, order
            time.sleep(.2)
        assert order["closeReason"] == "PAYMENT_TIMEOUT"
        assert call(client, "order/sandboxPay", orderId=order_id)["code"] == 409
        assert int(sql(f"SELECT COUNT(*) FROM ksdatabase_studio.commerce_stock_ledger WHERE order_id='{order_id}' AND event_type='RELEASE'")) == 1
        report["timeoutReleased"] = True
        expire_activity()
        assert balances(business(b, "GET", "/commerce/inventory")) == before
        assert balances(business(a, "GET", "/commerce/inventory")) == before_demo
        assert business(b, "GET", "/commerce/inventory/reconciliation")["healthy"]
        assert sql(historical_query) == historical
        report.update(historicalLedgerUnchanged=True, ledgerBalanced=True, inventoryRestored=True,
                      firstAttempts=first["attempts"], recoveredAttempts=second["attempts"])
        print("PASS simulated abandoned lease and committed-order acknowledgement recovery; no process was killed")
        return report
    except Exception as failure:
        report["failure"] = type(failure).__name__ + ": " + str(failure)[:1000]
        raise
    finally:
        try:
            expire_activity()
            if created and b is not None:
                # End first so any queued fixture cannot create a later hold after cleanup.
                rows = sql(f"SELECT order_id,owner_id FROM ksdatabase_studio.commerce_order WHERE activity_id='{activity}' AND shop_id='default' AND status=0")
                for row in rows.splitlines():
                    own_order, own_owner = row.split("\t")
                    business(b, "POST", "/commerce/orders/" + own_order + "/cancel", {"ownerId": own_owner})
                business(b, "GET", "/commerce/inventory")
        finally:
            report["elapsedSeconds"] = round(time.perf_counter() - started, 3)
            result_path.parent.mkdir(parents=True, exist_ok=True)
            result_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
            print(json.dumps(report, ensure_ascii=False))
            for handle in (client, foreign, a, b):
                if handle is not None: handle.close()


if __name__ == "__main__":
    run()
