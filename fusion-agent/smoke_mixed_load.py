"""Open-arrival mixed load against local Studio commerce, with retained audit records.

Example: .venv/Scripts/python.exe smoke_mixed_load.py --users 1000 --browse-rps 60 --seconds 120 --orders 200

Purchase-in 90% of the requested units through ERP, sell/ship those units, cancel
5%, and accelerate only this run's remaining unpaid deadlines for the real expiry
scheduler. Never reset stock, remove orders, or delete posted receipts. A failed
run may leave legitimate excess purchased stock; the JSON report exposes it.
"""
from __future__ import annotations

import argparse
import asyncio
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta
from hashlib import sha256
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import time
import uuid

import httpx

from bootstrap_tenants import MYSQL, CONFIG


def check(condition, message):
    if not condition:
        raise RuntimeError(str(message))


def pct(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(len(ordered) * fraction) - 1)], 3)


def stock_balances(rows):
    fields = ("bookStock", "onHand", "reservedStock", "activityStock", "availableStock")
    return {str(row["productId"]): {key: row[key] for key in fields} for row in rows}


def mysql(query):
    # Reuse the existing local CLI/config, without printing credentials or commands.
    result = subprocess.run(
        [str(MYSQL), "--defaults-extra-file=" + str(CONFIG), "--connect-timeout=5",
         "--default-character-set=utf8mb4", "-N", "-B"], input=query,
        capture_output=True, text=True, encoding="utf-8", timeout=30)
    check(result.returncode == 0, "MySQL verification failed: " + result.stderr[:400])
    return result.stdout.strip()


class Requests:
    def __init__(self, client, limit):
        self.client = client
        self.sem = asyncio.Semaphore(limit)
        self.started = time.perf_counter()
        self.phase = "setup"
        self.samples = []
        self.active = self.waiting = self.max_active = self.max_waiting = 0
        self.phase_active = Counter()
        self.phase_max_active = Counter()

    async def call(self, base, method, path, *, cookie="", token=None, body=None,
                   params=None, expected=(200,), planned=None, headers=None):
        entered = time.perf_counter()
        phase = self.phase
        queued = self.sem.locked()
        if queued:
            self.waiting += 1
            self.max_waiting = max(self.max_waiting, self.waiting)
        try:
            await self.sem.acquire()
        finally:
            if queued:
                self.waiting -= 1
        begin = time.perf_counter()
        self.active += 1
        self.max_active = max(self.max_active, self.active)
        self.phase_active[phase] += 1
        self.phase_max_active[phase] = max(self.phase_max_active[phase], self.phase_active[phase])
        status = code = 0
        error = None
        try:
            # Explicit Cookie on EVERY request prevents a shared httpx cookie jar
            # from making all virtual visitors accidentally share one identity.
            outgoing = {"Cookie": cookie, **(headers or {})}
            if token:
                outgoing["Authorization"] = token
            response = await self.client.request(method, base.rstrip("/") + path,
                                                 headers=outgoing, params=params, json=body)
            status = response.status_code
            payload = response.json()
            code = int(payload.get("code", status))
            check(code in expected, f"{method} {path}: HTTP {status}, code {code}, "
                  + str(payload.get("msg") or payload.get("info") or "unexpected response")[:180])
            return payload, response
        except BaseException as ex:
            error = type(ex).__name__
            raise
        finally:
            end = time.perf_counter()
            endpoint = re.sub(r"/commerce/orders/SC[0-9]{14}[A-F0-9]{10}", "/commerce/orders/{orderId}", path)
            endpoint = re.sub(r"/commerce/checkouts/[a-f0-9]{32}", "/commerce/checkouts/{jobId}", endpoint)
            self.samples.append({"endpoint": method + " " + endpoint, "phase": phase,
                                 "start": begin - self.started, "end": end - self.started,
                                 "latencyMs": (end - begin) * 1000, "code": code,
                                 "http": status, "error": error,
                                 "clientQueueMs": (begin - entered) * 1000,
                                 "startDelayMs": (begin - planned) * 1000 if planned is not None else 0})
            self.active -= 1
            self.phase_active[phase] -= 1
            self.sem.release()

    def summary(self, phase, elapsed):
        groups = defaultdict(list)
        for sample in self.samples:
            if sample["phase"] == phase:
                groups[sample["endpoint"]].append(sample)
        if phase != "load" and groups:
            rows = [row for group in groups.values() for row in group]
            elapsed = max(row["end"] for row in rows) - min(row["start"] for row in rows)
        result = {}
        for endpoint, rows in sorted(groups.items()):
            latencies = [row["latencyMs"] for row in rows]
            result[endpoint] = {"count": len(rows), "p50Ms": pct(latencies, .5),
                                "p95Ms": pct(latencies, .95), "p99Ms": pct(latencies, .99),
                                "maxMs": round(max(latencies), 3),
                                "measurementWindowSeconds": round(elapsed, 3),
                                "actualRPS": round(len(rows) / max(elapsed, .001), 3),
                                "codes": dict(Counter(str(row["code"]) for row in rows)),
                                "httpCodes": dict(Counter(str(row["http"]) for row in rows)),
                                "errors": dict(Counter(row["error"] for row in rows if row["error"])),
                                "clientQueueP95Ms": pct([row["clientQueueMs"] for row in rows], .95),
                                "scheduledStartDelayP95Ms": pct([row["startDelayMs"] for row in rows], .95),
                                "latenciesMs": [round(value, 3) for value in latencies]}
        return result


class MixedRun:
    def __init__(self, args):
        self.args = args
        self.run_id = uuid.uuid4().hex[:12]
        self.activity = "mixed_" + self.run_id
        self.purchase = "MIXED_IN_" + self.run_id
        self.paid_count = args.orders * 90 // 100
        self.cancel_count = args.orders * 5 // 100
        self.timeout_count = args.orders - self.paid_count - self.cancel_count
        self.sessions = []
        self.addresses = []
        self.jobs = {}
        self.orders = {}
        self.errors = []
        self.browse_errors = []
        self.resources = []
        self.queue_samples = []
        self.schedule_lags = []
        self.completions = []
        self.timeout_latencies = []
        self.started = time.perf_counter()
        self.load_seconds = 0
        self.browse_completed = 0
        self.visitors_observed = set()
        self.merchant = self.admin = None
        self.pid = self.warehouse = None
        self.before_studio = self.before_demo = None
        self.before_warehouses = self.before_demo_warehouses = None
        self.historical_hash = None
        self.ledger_max = 0
        self.activity_created = self.purchase_posted = False
        self.expiry_clock_accelerated = False
        self.stop_monitor = asyncio.Event()
        self.report = {
            "runId": self.run_id, "passed": False, "tenantId": "studio", "shopId": args.shop_id,
            "users": args.users, "targetBrowseRPS": args.browse_rps, "arrivalWindowSeconds": args.seconds,
            "targetOrderArrivalRPS": args.order_rps if args.order_rps is not None else args.orders / args.seconds,
            "requestedOrders": args.orders, "expectedPaidShippedReceived": self.paid_count,
            "expectedCancelled": self.cancel_count, "expectedTimedOut": self.timeout_count,
            "activityId": self.activity, "purchaseReceipt": self.purchase,
            "paymentProvider": "LOCAL_SANDBOX", "logistics": "SIMULATED",
            "expiryClockAccelerated": False, "inventoryRestored": False,
            "machine": {"platform": platform.platform(), "logicalCPUs": os.cpu_count(),
                        "python": platform.python_version()},
            "limitations": ["Local host, sandbox payment and simulated carrier; not a production capacity guarantee.",
                            "Prepared visitor sessions are distinct identities, not simultaneous checkout requests.",
                            "Configured open arrival rates are measured workloads, not maximum sustainable throughput.",
                            "No LLM calls, CDN traffic or multi-Java-instance failover are exercised."]}

    async def query(self, statement):
        return await asyncio.to_thread(mysql, statement)

    async def java(self, method, path, body=None, *, platform_admin=False, expected=(200,), params=None, headers=None):
        payload, _ = await self.http.call(self.args.java_url, method, path,
                                           token=self.admin if platform_admin else self.merchant,
                                           headers={"X-Shop-ID": self.args.shop_id, **(headers or {})},
                                           body=body, params=params, expected=expected)
        return payload.get("data") if payload.get("code") == 200 else payload

    async def customer(self, index, method, path, *, body=None, params=None, expected=(200,), planned=None, base=None):
        self.visitors_observed.add(index)
        payload, _ = await self.http.call(base or self.args.store_url, method, "/api/" + path,
                                           cookie="simlect_studio_session=" + self.sessions[index],
                                           body=body, params=params, expected=expected, planned=planned)
        return payload

    async def login(self, user):
        payload, _ = await self.http.call(self.args.java_url, "POST", "/login", body={
            "username": user, "password": os.getenv("MIXED_LOAD_ADMIN_PASSWORD", "admin123")})
        check(payload.get("token"), "Missing administrator token")
        return "Bearer " + payload["token"]

    async def warehouse_snapshot(self, database):
        return await self.query(f"SELECT product_id,warehouse_id,SUM(plan_quantity) FROM {database}.inventory_product GROUP BY product_id,warehouse_id ORDER BY product_id,warehouse_id")

    async def prepare(self):
        self.admin, self.merchant = await asyncio.gather(self.login("admin"), self.login("studio_admin"))
        check((await self.java("GET", "/commerce/context"))["tenantId"] == "studio", "Merchant is not bound to Studio")
        check((await self.java("GET", "/commerce/context", platform_admin=True))["tenantId"] == "demo", "Platform account is not demo")
        studio, demo = await asyncio.gather(self.java("GET", "/commerce/inventory"),
                                           self.java("GET", "/commerce/inventory", platform_admin=True))
        self.before_studio, self.before_demo = stock_balances(studio), stock_balances(demo)
        check((await self.java("GET", "/commerce/inventory/reconciliation"))["healthy"], "Initial reconciliation is unhealthy")
        product = next((row for row in studio if row["productCode"] == self.args.product_code), None)
        check(product is not None, "Requested Studio SKU does not exist")
        check(product.get("snapshotReady", True) and product.get("listed") in (1, "1", True), "SKU is not published/initialized")
        check(int(product["availableStock"]) >= self.args.orders - self.paid_count,
              f"Baseline available stock must be >= {self.args.orders - self.paid_count}; no changes made")
        self.pid = int(product["productId"])
        self.report["productId"] = self.pid
        self.before_warehouses, self.before_demo_warehouses = await asyncio.gather(
            self.warehouse_snapshot(self.args.studio_database), self.warehouse_snapshot(self.args.demo_database))
        warehouse = await self.query(f"SELECT warehouse_id FROM {self.args.studio_database}.inventory_product WHERE product_id={self.pid} AND warehouse_id>0 AND plan_quantity>0 ORDER BY warehouse_id,inventory_id LIMIT 1")
        check(warehouse, "SKU needs an existing positive warehouse balance")
        self.warehouse = int(warehouse)
        self.ledger_max = int(await self.query(f"SELECT COALESCE(MAX(ledger_id),0) FROM {self.args.studio_database}.commerce_stock_ledger WHERE product_id={self.pid}"))
        self.historical_hash = sha256((await self.query(self.historical_sql())).encode()).hexdigest()
        self.report["baseline"] = {"studio": self.before_studio, "demo": self.before_demo,
                                   "warehouseId": self.warehouse, "historicalLedgerSHA256": self.historical_hash}
        # Create distinct sessions with one shared HTTP pool; explicit empty Cookie is mandatory.
        setup_sem = asyncio.Semaphore(self.args.setup_concurrency)
        async def session():
            async with setup_sem:
                _, response = await self.http.call(self.args.store_url, "GET", "/api/account/autoLogin")
                value = response.cookies.get("simlect_studio_session")
                check(value and re.fullmatch(r"[A-Za-z0-9_-]+", value), "Studio session cookie missing")
                address, _ = await self.http.call(self.args.store_url, "POST", "/api/userAddress/addAddress",
                    cookie="simlect_studio_session=" + value,
                    body={"addressee":"负载练习访客","phone":"00000000000","address":"虚构地址：样例市负载测试空间","defaultType":1})
                return value, address["data"]["addressId"]
        visitors = await asyncio.gather(*(session() for _ in range(self.args.users)))
        self.sessions = [visitor[0] for visitor in visitors]
        self.addresses = [visitor[1] for visitor in visitors]
        check(len(set(self.sessions)) == self.args.users, "Virtual visitors share a session")
        self.report["distinctSessionsPrepared"] = len(set(self.sessions))
        if self.args.peer_store_url:
            for index in range(min(5, len(self.sessions))):
                response = await self.customer(index, "GET", "account/getUserInfo", base=self.args.peer_store_url)
                check(response["data"]["tenantId"] == "studio", "Shared session failed on peer facade")
            self.report["peerFacadeSessionVerified"] = True
        payload = {
            "systematicReceipt": self.purchase, "originalReceipt": "Mixed load " + self.run_id,
            "receiptCategory": 1, "receiptType": 1, "receiptStatus": 2,
            "invoiceDate": date.today().isoformat(), "warehousingIds": str(self.warehouse),
            "retrievalIds": "0", "userIds": "101", "supplierIds": "0", "customerIds": "0",
            "deposit": "0", "totalAmount": str(70 * self.paid_count), "receiptNotes": "Mixed load sandbox procurement",
            "details": [{"productId": str(self.pid), "warehousingId": str(self.warehouse), "retrievalId": "0",
                         "supplierId": "0", "customerId": "0", "measureUnit": "件",
                         "productSpecifications": product.get("spec") or "Zigbee/9W",
                         "planQuantity": str(self.paid_count), "univalence": "70", "discount": "100",
                         "money": str(70 * self.paid_count), "cost": "70",
                         "currentInventory": str(product["bookStock"]),
                         "actualInventory": str(int(product["bookStock"]) + self.paid_count), "remarks": "Mixed load units"}]}
        await self.java("POST", "/purchase/purchaseReceiptProcessing/save", payload)
        self.purchase_posted = True
        await self.java("POST", "/purchase/purchaseReceiptProcessing/save", payload)
        current = next(row for row in await self.java("GET", "/commerce/inventory") if int(row["productId"]) == self.pid)
        check(int(current["bookStock"]) == int(product["bookStock"]) + self.paid_count, "Procurement was not applied exactly once")
        now = datetime.now()
        await self.java("POST", "/commerce/activities", {
            "activityId": self.activity, "productId": self.pid, "title": "Mixed load " + self.run_id,
            "capacity": self.args.orders, "perOwnerLimit": 1, "price": 99,
            "startsAt": (now - timedelta(seconds=5)).isoformat(timespec="seconds"),
            "endsAt": (now + timedelta(seconds=self.args.seconds + self.args.drain_timeout + 600)).isoformat(timespec="seconds")})
        self.activity_created = True
        public = await self.customer(0, "GET", "seckill/listActivities")
        check(not any("myOrderId" in row or "participationCount" in row for row in public["data"]), "Public activity response leaks personal state")

    def historical_sql(self):
        return f"SELECT * FROM {self.args.studio_database}.commerce_stock_ledger WHERE product_id={self.pid} AND ledger_id<={self.ledger_max} ORDER BY ledger_id"

    async def retry_submit(self, index, planned=None):
        deadline = time.monotonic() + self.args.drain_timeout
        body = {"activityId": self.activity, "requestKey": self.run_id + "_" + str(index), "addressId": self.addresses[index]}
        attempt = 0
        while True:
            try:
                response = await self.customer(index, "POST", "seckill/submitOrder", body=body,
                                               expected=(200, 429, 502, 503), planned=planned if attempt == 0 else None)
                if response["code"] == 200:
                    return response["data"]
            except httpx.HTTPError:
                pass  # Unknown outcome: retry only the SAME idempotency key.
            check(time.monotonic() < deadline, "Checkout admission deadline exceeded")
            attempt += 1
            await asyncio.sleep(min(2, .2 * 2 ** min(attempt, 4)))

    async def wait_job(self, index, receipt):
        deadline = time.monotonic() + self.args.drain_timeout
        delay = .25
        while receipt["state"] not in ("SUCCEEDED", "REJECTED"):
            check(time.monotonic() < deadline, "Checkout worker deadline exceeded")
            await asyncio.sleep(delay)
            receipt = (await self.customer(index, "GET", "seckill/getCheckoutStatus", params={"jobId": receipt["jobId"]}))["data"]
            delay = min(2, delay * 1.5)
        check(receipt["state"] == "SUCCEEDED", "Expected successful order, got " + str(receipt))
        return receipt

    async def wait_closed(self, index, order_id):
        begin = time.perf_counter()
        deadline = time.monotonic() + self.args.drain_timeout
        while True:
            order = (await self.customer(index, "GET", "order/getMyOrderDetail", params={"orderId": order_id}))["data"]
            if order["orderStatus"] == 4:
                check(order["closeReason"] == "PAYMENT_TIMEOUT", "Timeout order was closed for another reason")
                self.timeout_latencies.append((time.perf_counter() - begin) * 1000)
                return
            check(time.monotonic() < deadline, "Real expiry scheduler did not close the order")
            await asyncio.sleep(1)

    async def order_flow(self, index, planned):
        try:
            receipt = await self.retry_submit(index, planned)
            self.jobs[index] = receipt
            receipt = await self.wait_job(index, receipt)
            self.jobs[index] = receipt
            order_id = receipt["orderId"]
            self.orders[index] = order_id
            self.completions.append((time.perf_counter() - planned) * 1000)
            if index < min(3, self.paid_count):
                repeated = await self.retry_submit(index)
                check(repeated["jobId"] == receipt["jobId"] and repeated.get("orderId") == order_id, "Duplicate checkout changed its result")
            if index < self.paid_count:
                payment = {"orderId": order_id, "paymentRequestId": "mixedpay_" + self.run_id + "_" + str(index), "scenario": "success"}
                paid = await self.customer(index, "POST", "order/sandboxPay", body=payment)
                check(paid["data"]["orderStatus"] == 1, "Sandbox payment did not succeed")
                if index < min(3, self.paid_count):
                    duplicates = await asyncio.gather(*(self.customer(index, "POST", "order/sandboxPay", body=payment) for _ in range(2)))
                    check(all(row["data"]["orderStatus"] == 1 for row in duplicates), "Idempotent payment failed")
                shipped = await self.java("POST", "/commerce/orders/" + order_id + "/ship", {})
                check(shipped["orderStatus"] == 2, "Dispatch did not succeed")
                received = await self.customer(index, "POST", "order/receiveOrder", body={"orderId": order_id})
                check(received["data"]["orderStatus"] == 3, "Receipt confirmation did not succeed")
            elif index < self.paid_count + self.cancel_count:
                cancelled = await self.customer(index, "POST", "order/cancelOrder", body={"orderId": order_id})
                check(cancelled["data"]["orderStatus"] == 4, "Cancellation did not succeed")
            else:
                await self.query(f"UPDATE {self.args.studio_database}.commerce_order SET expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE order_id='{order_id}' AND activity_id='{self.activity}' AND shop_id='{self.args.shop_id}' AND status=0")
                self.expiry_clock_accelerated = True
                await self.wait_closed(index, order_id)
        except Exception as ex:
            self.errors.append({"index": index, "stage": "order", "error": type(ex).__name__ + ": " + str(ex)[:400]})

    async def browse(self, sequence, planned):
        index = sequence % self.args.users
        slot = sequence % 10
        if slot < 4:
            path, params = "seckill/listActivities", None
        elif slot < 7:
            path, params = "product/loadProduct", {"pageNo": 1, "pageSize": 20}
        elif slot < 9:
            path, params = "product/getProduct", {"productId": str(self.pid)}
        else:
            path, params = "seckill/myParticipation", None
        try:
            await self.customer(index, "GET", path, params=params, planned=planned)
            self.browse_completed += 1
        except Exception as ex:
            self.browse_errors.append({"sequence": sequence, "error": type(ex).__name__ + ": " + str(ex)[:200]})

    async def schedule(self, start, count, rate, operation, tasks):
        for index in range(count):
            planned = start + index / rate
            await asyncio.sleep(max(0, planned - time.perf_counter()))
            self.schedule_lags.append((time.perf_counter() - planned) * 1000)
            tasks.append(asyncio.create_task(operation(index, planned)))

    async def resource_sample(self):
        command = shutil.which("powershell.exe") or shutil.which("powershell")
        if not command:
            return {"unavailable": "PowerShell not installed", "elapsedSeconds": round(time.perf_counter() - self.started, 3)}
        script = """[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new(); $os=Get-CimInstance Win32_OperatingSystem; $cpu=Get-CimInstance Win32_Processor; $p=@(Get-Process | Where-Object {$_.ProcessName -match '^(java|python|mysqld|redis-server|node)$'} | Select-Object Id,ProcessName,CPU,WorkingSet64,PrivateMemorySize64); @{totalMemoryBytes=[long]$os.TotalVisibleMemorySize*1024;freeMemoryBytes=[long]$os.FreePhysicalMemory*1024;systemCPUPercent=($cpu|Measure-Object LoadPercentage -Average).Average;processes=$p}|ConvertTo-Json -Depth 4 -Compress"""
        process = await asyncio.create_subprocess_exec(command, "-NoProfile", "-NonInteractive", "-Command", script,
                                                       stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        try:
            output, _ = await asyncio.wait_for(process.communicate(), timeout=12)
            check(process.returncode == 0, "Resource sampler command failed")
            sample = json.loads(output.decode("utf-8-sig"))
        except BaseException:
            if process.returncode is None:
                process.kill()
                await process.communicate()
            raise
        sample["elapsedSeconds"] = round(time.perf_counter() - self.started, 3)
        previous = next((row for row in reversed(self.resources) if "processes" in row), None)
        if previous:
            span = sample["elapsedSeconds"] - previous["elapsedSeconds"]
            prior = {row["Id"]: row for row in previous.get("processes") or []}
            for row in sample.get("processes") or []:
                old = prior.get(row["Id"])
                if old and row.get("CPU") is not None and old.get("CPU") is not None and span > 0:
                    cpu = max(0, float(row["CPU"]) - float(old["CPU"])) * 100 / span
                    row["cpuPercentOfOneCore"] = round(cpu, 2)
                    row["cpuPercentOfMachine"] = round(cpu / (os.cpu_count() or 1), 2)
        return sample

    async def monitor(self):
        while not self.stop_monitor.is_set():
            try:
                self.resources.append(await self.resource_sample())
                self.queue_samples.append({"elapsedSeconds": round(time.perf_counter() - self.started, 3),
                                           "shopMetrics": await self.java("GET", "/commerce/queue/metrics")})
            except Exception as ex:
                self.resources.append({"error": type(ex).__name__ + ": " + str(ex)[:200]})
            try:
                await asyncio.wait_for(self.stop_monitor.wait(), timeout=self.args.sample_interval)
            except asyncio.TimeoutError:
                pass

    async def load(self):
        self.http.phase = "load"
        self.visitors_observed.clear()
        started = time.perf_counter()
        self.report["loadStartedAt"] = datetime.now().isoformat(timespec="seconds")
        tasks = []
        monitor = asyncio.create_task(self.monitor())
        browse_count = math.ceil(self.args.browse_rps * self.args.seconds)
        try:
            await asyncio.gather(
                self.schedule(started, browse_count, self.args.browse_rps, self.browse, tasks),
                self.schedule(started, self.args.orders, self.report["targetOrderArrivalRPS"], self.order_flow, tasks))
            done, pending = await asyncio.wait(tasks, timeout=self.args.drain_timeout)
            self.report["unfinishedTasksAtDrainDeadline"] = len(pending)
            if pending:
                for task in pending:
                    task.cancel()
                await asyncio.gather(*pending, return_exceptions=True)
                self.errors.append({"stage": "drain", "error": f"{len(pending)} tasks did not finish"})
            # Retrieve unexpected task failures rather than silently dropping them.
            for task in done:
                if not task.cancelled() and task.exception():
                    self.errors.append({"stage": "task", "error": str(task.exception())[:400]})
        finally:
            unfinished = [task for task in tasks if not task.done()]
            for task in unfinished:
                task.cancel()
            if unfinished:
                await asyncio.gather(*unfinished, return_exceptions=True)
            self.load_seconds = time.perf_counter() - started
            self.stop_monitor.set()
            await monitor
        self.report.update({"loadElapsedSeconds": round(self.load_seconds, 3),
                            "scheduledBrowseRequests": browse_count, "successfulBrowseRequests": self.browse_completed,
                            "actualBrowseRPS": round(self.browse_completed / max(self.load_seconds, .001), 3),
                            "visitorsObservedDuringLoad": len(self.visitors_observed),
                            "successfulCheckoutOrders": len(self.orders),
                            "successfulCheckoutOrdersPerSecond": round(len(self.orders) / max(self.load_seconds, .001), 3),
                            "checkoutCompletionP50Ms": pct(self.completions, .5),
                            "checkoutCompletionP95Ms": pct(self.completions, .95),
                            "checkoutCompletionP99Ms": pct(self.completions, .99),
                            "timeoutSchedulerReleaseP95Ms": pct(self.timeout_latencies, .95),
                            "schedulerLagP95Ms": pct(self.schedule_lags, .95),
                            "schedulerLagMaxMs": round(max(self.schedule_lags, default=0), 3)})
        check(not self.errors and not self.browse_errors, "Mixed workload had failed or unfinished operations")
        check(len(self.orders) == self.args.orders, "Not every requested order succeeded")
        check(len(set(self.orders.values())) == self.args.orders, "Distinct visitors received duplicate orders")

    async def isolation_and_idempotency(self):
        self.http.phase = "verify"
        receipt, order_id = self.jobs[0], self.orders[0]
        foreign = await self.customer(1, "GET", "seckill/getCheckoutStatus", params={"jobId": receipt["jobId"]}, expected=(404,))
        check(foreign["code"] == 404, "Foreign owner can read checkout")
        foreign = await self.customer(1, "GET", "order/getMyOrderDetail", params={"orderId": order_id}, expected=(404,))
        check(foreign["code"] == 404, "Foreign owner can read order")
        check((await self.java("GET", "/commerce/orders/" + order_id, platform_admin=True, expected=(404,)))["code"] == 404, "Cross-tenant order leaked")
        owner = sha256(self.sessions[0].encode()).hexdigest()
        check((await self.java("GET", "/commerce/checkouts/" + receipt["jobId"], platform_admin=True,
                               params={"ownerId": owner}, expected=(404,)))["code"] == 404, "Cross-tenant checkout leaked")
        check((await self.java("GET", "/commerce/context", headers={"X-Tenant-ID": "demo"}, expected=(403,)))["code"] == 403, "Forged tenant header accepted")
        sample = []
        for index in range(min(3, self.paid_count)):
            oid = self.orders[index]
            counts = await self.query(
                f"SELECT (SELECT COUNT(*) FROM {self.args.studio_database}.commerce_stock_hold WHERE order_id='{oid}'),"
                f"(SELECT COUNT(*) FROM {self.args.studio_database}.commerce_stock_ledger WHERE order_id='{oid}' AND event_type='RESERVE'),"
                f"(SELECT COUNT(*) FROM {self.args.studio_database}.commerce_payment_attempt WHERE order_id='{oid}'),"
                f"(SELECT COUNT(*) FROM {self.args.studio_database}.commerce_order WHERE order_id='{oid}')")
            check(counts == "1\t1\t1\t1", "Duplicate request/payment created extra records: " + counts)
            sample.append({"orderId": oid, "holds": 1, "reserveEvents": 1, "paymentAttempts": 1, "orders": 1})
        self.report["duplicateRequestAndPaymentSamples"] = sample
        self.report["ownerAndTenantIsolationVerified"] = True

    async def cleanup(self):
        """Only finish this run's real transactions. Never manufacture a stock reset."""
        self.http.phase = "cleanup"
        if not self.merchant:
            return
        exists = int(await self.query(f"SELECT COUNT(*) FROM {self.args.studio_database}.commerce_activity WHERE activity_id='{self.activity}' AND shop_id='{self.args.shop_id}'"))
        if not exists:
            return
        self.activity_created = True
        # This clock change blocks behind any in-flight activity transaction. Future
        # queued submissions then reject naturally, instead of creating late holds.
        await self.query(f"UPDATE {self.args.studio_database}.commerce_activity SET ends_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE activity_id='{self.activity}' AND shop_id='{self.args.shop_id}'")
        deadline = time.monotonic() + min(self.args.drain_timeout, 60)
        while True:
            pending = int(await self.query(f"SELECT COUNT(*) FROM {self.args.studio_database}.commerce_checkout_job WHERE activity_id='{self.activity}' AND shop_id='{self.args.shop_id}' AND state IN ('PENDING','PROCESSING')"))
            if not pending or time.monotonic() >= deadline:
                self.report["pendingJobsAfterCleanupWait"] = pending
                break
            await asyncio.sleep(1)
        rows = await self.query(f"SELECT order_id,owner_id FROM {self.args.studio_database}.commerce_order WHERE activity_id='{self.activity}' AND shop_id='{self.args.shop_id}' ORDER BY order_id")
        cleanup_sem = asyncio.Semaphore(8)
        actions, failures = [], []
        async def finish(line):
            oid, owner = line.split("\t")
            async with cleanup_sem:
                for attempt in range(3):
                    try:
                        current = await self.java("GET", "/commerce/orders/" + oid)
                        state = current["orderStatus"]
                        if state == 0:
                            await self.java("POST", "/commerce/orders/" + oid + "/cancel", {"ownerId": owner})
                            actions.append({"orderId": oid, "action": "cancel-unpaid"})
                        elif state == 1:
                            await self.java("POST", "/commerce/orders/" + oid + "/ship", {})
                            await self.java("POST", "/commerce/orders/" + oid + "/receive", {"ownerId": owner})
                            actions.append({"orderId": oid, "action": "ship-and-receive-paid"})
                        elif state == 2:
                            await self.java("POST", "/commerce/orders/" + oid + "/receive", {"ownerId": owner})
                            actions.append({"orderId": oid, "action": "receive-shipped"})
                        return
                    except Exception as ex:
                        if attempt == 2:
                            failures.append({"orderId": oid, "error": type(ex).__name__ + ": " + str(ex)[:240]})
                        else:
                            await asyncio.sleep(1)
        await asyncio.gather(*(finish(line) for line in rows.splitlines()))
        self.report["cleanupActions"] = actions
        self.report["cleanupFailures"] = failures
        # The actual scheduled maintenance must release activity allocation. Reads
        # intentionally cannot perform expiry or repair balances anymore.
        deadline = time.monotonic() + min(self.args.drain_timeout, 30)
        while True:
            events = int(await self.query(f"SELECT COUNT(*) FROM {self.args.studio_database}.commerce_stock_ledger WHERE event_key='ACTIVITY_EXPIRE:{self.activity}:{self.pid}'"))
            if events or time.monotonic() >= deadline:
                self.report["activityReleasedByScheduler"] = events == 1
                break
            await asyncio.sleep(.5)

    async def final_evidence(self):
        self.http.phase = "verify"
        if self.before_studio is None:
            return
        after_studio, after_demo, warehouses, demo_warehouses = await asyncio.gather(
            self.java("GET", "/commerce/inventory"), self.java("GET", "/commerce/inventory", platform_admin=True),
            self.warehouse_snapshot(self.args.studio_database), self.warehouse_snapshot(self.args.demo_database))
        studio = stock_balances(after_studio)
        demo = stock_balances(after_demo)
        self.report["finalBalances"] = {"studio": studio, "demo": demo}
        self.report["inventoryRestored"] = studio == self.before_studio
        self.report["warehouseBalancesRestored"] = warehouses == self.before_warehouses
        self.report["otherTenantUnchanged"] = demo == self.before_demo and demo_warehouses == self.before_demo_warehouses
        reconciliation = await self.java("GET", "/commerce/inventory/reconciliation")
        self.report["reconciliation"] = reconciliation
        if self.pid is not None:
            actual_hash = sha256((await self.query(self.historical_sql())).encode()).hexdigest()
            self.report["historicalLedgerUnchanged"] = actual_hash == self.historical_hash
            deltas = await self.query(
                f"SELECT COALESCE(SUM(delta_on_hand),0),COALESCE(SUM(delta_reserved),0),COALESCE(SUM(delta_activity),0),COUNT(*),COUNT(DISTINCT event_key) FROM {self.args.studio_database}.commerce_stock_ledger WHERE product_id={self.pid} AND (activity_id='{self.activity}' OR reason='ERP_SAVE:{self.purchase}')")
            values = [int(value) for value in deltas.split("\t")]
            self.report["runLedger"] = {"deltaOnHand": values[0], "deltaReserved": values[1], "deltaActivity": values[2], "events": values[3], "uniqueEvents": values[4]}
            self.report["ledgerBalanced"] = values[:3] == [0, 0, 0] and values[3] == values[4]
        states = await self.query(f"SELECT status,COALESCE(close_reason,''),COUNT(*) FROM {self.args.studio_database}.commerce_order WHERE activity_id='{self.activity}' AND shop_id='{self.args.shop_id}' GROUP BY status,close_reason ORDER BY status,close_reason")
        self.report["finalOrderStates"] = [{"status": int(row.split("\t")[0]), "closeReason": row.split("\t")[1], "count": int(row.split("\t")[2])} for row in states.splitlines()]
        shipped = sum(row["count"] for row in self.report["finalOrderStates"] if row["status"] in (2, 3))
        inbound = await self.query(f"SELECT COALESCE(SUM(delta_quantity),0),COUNT(*) FROM {self.args.studio_database}.commerce_warehouse_ledger WHERE receipt_id='{self.purchase}'")
        units, events = [int(value) for value in inbound.split("\t")]
        self.report["procurementAppliedOnce"] = units == self.paid_count and events == 1
        self.report["residualPurchasedUnits"] = units - shipped
        self.report["residualNote"] = ("No residual purchased units." if units == shipped else
                                       "Purchased units not dispatched remain real stock. No reset or audit deletion was performed.")
        self.report["noActiveRunHolds"] = int(await self.query(f"SELECT COUNT(*) FROM {self.args.studio_database}.commerce_stock_hold h JOIN {self.args.studio_database}.commerce_order o ON o.order_id=h.order_id WHERE o.activity_id='{self.activity}' AND o.shop_id='{self.args.shop_id}' AND h.status='RESERVED'")) == 0

    async def run(self):
        limits = httpx.Limits(max_connections=self.args.max_inflight, max_keepalive_connections=min(64, self.args.max_inflight))
        async with httpx.AsyncClient(timeout=httpx.Timeout(30, connect=5, pool=20), limits=limits, trust_env=False) as client:
            self.http = Requests(client, self.args.max_inflight)
            failure = None
            try:
                await self.prepare()
                await self.load()
                await self.isolation_and_idempotency()
            except Exception as ex:
                failure = type(ex).__name__ + ": " + str(ex)[:1000]
            finally:
                try:
                    await self.cleanup()
                except Exception as ex:
                    self.report["cleanupError"] = type(ex).__name__ + ": " + str(ex)[:500]
                try:
                    await self.final_evidence()
                except Exception as ex:
                    self.report["verificationError"] = type(ex).__name__ + ": " + str(ex)[:500]
                elapsed = time.perf_counter() - self.started
                self.report.update({"elapsedSeconds": round(elapsed, 3),
                                    "expiryClockAccelerated": self.expiry_clock_accelerated,
                                    "maxInFlightHTTP": self.http.max_active,
                                    "maxInFlightLoad": self.http.phase_max_active["load"],
                                    "maxClientWaitingForSlot": self.http.max_waiting,
                                    "endpointMetricsLoad": self.http.summary("load", self.load_seconds or elapsed),
                                    "endpointMetricsSetup": self.http.summary("setup", elapsed),
                                    "endpointMetricsVerification": self.http.summary("verify", elapsed),
                                    "endpointMetricsCleanup": self.http.summary("cleanup", elapsed),
                                    "orderFailures": self.errors, "browseFailures": self.browse_errors,
                                    "resourceSamples": self.resources, "queueSamples": self.queue_samples})
                if failure:
                    self.report["failure"] = failure
                expected_states = {(3, ""): self.paid_count, (4, "CUSTOMER_CANCEL"): self.cancel_count,
                                   (4, "PAYMENT_TIMEOUT"): self.timeout_count}
                actual_states = {(row["status"], row["closeReason"]): row["count"] for row in self.report.get("finalOrderStates", [])}
                required = ("inventoryRestored", "warehouseBalancesRestored", "otherTenantUnchanged", "historicalLedgerUnchanged",
                            "ledgerBalanced", "procurementAppliedOnce", "noActiveRunHolds", "activityReleasedByScheduler",
                            "ownerAndTenantIsolationVerified")
                self.report["passed"] = (failure is None and not self.errors and not self.browse_errors
                                         and all(self.report.get(key) is True for key in required)
                                         and self.report.get("reconciliation", {}).get("healthy") is True
                                         and actual_states == expected_states
                                         and not self.report.get("cleanupFailures")
                                         and not self.report.get("cleanupError") and not self.report.get("verificationError")
                                         and self.report.get("pendingJobsAfterCleanupWait", 1) == 0)
                destination = Path(self.args.out)
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_text(json.dumps(self.report, ensure_ascii=False, indent=2), encoding="utf-8")
                compact = {key: self.report.get(key) for key in (
                    "passed", "runId", "distinctSessionsPrepared", "successfulCheckoutOrders", "actualBrowseRPS",
                    "checkoutCompletionP95Ms", "maxInFlightHTTP", "maxInFlightLoad", "inventoryRestored", "warehouseBalancesRestored",
                    "otherTenantUnchanged", "residualPurchasedUnits", "failure", "cleanupError", "verificationError")}
                print(json.dumps(compact, ensure_ascii=False))
                print("Report:", destination.resolve())
        return 0 if self.report["passed"] else 1


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--users", type=int, default=1000)
    parser.add_argument("--browse-rps", type=float, default=60)
    parser.add_argument("--seconds", type=float, default=120)
    parser.add_argument("--orders", type=int, default=200)
    parser.add_argument("--order-rps", type=float, default=None,
                        help="Optional open order arrival rate; default spreads all orders across --seconds")
    parser.add_argument("--max-inflight", type=int, default=128)
    parser.add_argument("--setup-concurrency", type=int, default=24)
    parser.add_argument("--drain-timeout", type=float, default=120)
    parser.add_argument("--sample-interval", type=float, default=10)
    parser.add_argument("--java-url", default="http://127.0.0.1:8035")
    parser.add_argument("--store-url", default="http://127.0.0.1:7051")
    parser.add_argument("--peer-store-url", default=None)
    parser.add_argument("--studio-database", default="ksdatabase_studio")
    parser.add_argument("--demo-database", default="ksdatabase")
    parser.add_argument("--shop-id", default="default")
    parser.add_argument("--product-code", default="DEMO-LAMP-ZB")
    parser.add_argument("--out", default=str(Path(__file__).resolve().parent.parent / "logs" / "mixed-load-result.json"))
    args = parser.parse_args()
    if not 20 <= args.orders <= 1000 or not args.orders <= args.users <= 100000:
        parser.error("orders must be 20..1000; users must be >= orders and <= 100000")
    if not 1 <= args.browse_rps <= 5000 or not 1 <= args.seconds <= 3600:
        parser.error("browse-rps must be 1..5000; seconds must be 1..3600")
    if args.order_rps is not None and (not math.isfinite(args.order_rps) or args.order_rps <= 0
                                      or args.orders / args.order_rps > args.seconds + 1e-9):
        parser.error("order-rps must be positive and orders/order-rps must fit within seconds")
    if not 1 <= args.max_inflight <= 1024 or not 1 <= args.setup_concurrency <= args.max_inflight:
        parser.error("max-inflight must be 1..1024; setup-concurrency must fit max-inflight")
    if args.drain_timeout < 10 or args.sample_interval < 1:
        parser.error("drain-timeout must be >=10; sample-interval must be >=1")
    for value in (args.studio_database, args.demo_database):
        if not re.fullmatch(r"[A-Za-z0-9_]+", value):
            parser.error("database identifiers must be alphanumeric/underscore")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", args.shop_id):
        parser.error("shop-id is invalid")
    # The adapter identities/cookie and ERP user binding are intentionally Studio.
    # This verification must not silently target an arbitrary remote production host.
    from urllib.parse import urlparse
    for value in (args.java_url, args.store_url, args.peer_store_url):
        if value and urlparse(value).hostname not in ("127.0.0.1", "localhost", "::1"):
            parser.error("This sandbox workload accepts loopback service URLs only")
    return args


if __name__ == "__main__":
    sys.exit(asyncio.run(MixedRun(arguments()).run()))
