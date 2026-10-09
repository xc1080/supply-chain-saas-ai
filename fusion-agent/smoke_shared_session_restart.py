"""Local-only Studio facade restart smoke; explicitly stops/restarts port 7051.

Run between other smoke/load tests. Creates only a fresh visitor and a one-unit
cart; does not create orders or change Java/Redis services. Cookies stay in RAM.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import time
import uuid
from urllib.parse import urlparse

import httpx
import redis

from shared_store import RedisSharedStore


ROOT = Path(__file__).resolve().parent.parent
BASE = "http://127.0.0.1:7051"
COOKIE = "simlect_studio_session"
REPORT = ROOT / "logs" / "shared-session-restart-result.json"

# All paths are trusted environment variables supplied below, never shell-built
# from a cookie, credential, HTTP field or command output.
MANAGE_FACADE = r"""
$ErrorActionPreference = 'Stop'
function Get-VerifiedOwner {
    $connections = @(Get-NetTCPConnection -LocalPort 7051 -State Listen -ErrorAction SilentlyContinue)
    $owners = @($connections | Select-Object -ExpandProperty OwningProcess -Unique)
    if ($owners.Count -eq 0) { return $null }
    if ($owners.Count -ne 1) { throw 'Port 7051 has multiple owners; refusing restart' }
    $worker = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $owners[0])
    if ($null -eq $worker -or $worker.Name -notmatch '^python(?:w)?\.exe$') {
        throw 'Port 7051 is not owned by a Python facade; refusing restart'
    }
    $command = $worker.CommandLine
    if ($command -notmatch '(?i)\s-m\s+uvicorn\s+main:app(?:\s|$)' -or
        $command -notmatch '--port(?:\s+|=)7051(?:\s|$)' -or
        $command -notmatch '--host(?:\s+|=)127\.0\.0\.1(?:\s|$)') {
        throw 'Port 7051 command does not match the expected local uvicorn main:app; refusing restart'
    }
    return [int]$worker.ProcessId
}
$previous = Get-VerifiedOwner
if ($env:FUSION_RESTART_MODE -eq 'inspect') {
    if ($null -eq $previous) { throw 'Studio facade is not listening on port 7051' }
    @{ listenerPid=$previous; verified=$true } | ConvertTo-Json -Compress
    exit 0
}
if ($env:FUSION_RESTART_MODE -eq 'ensure' -and $null -ne $previous) {
    @{ listenerPid=$previous; verified=$true; launched=$false } | ConvertTo-Json -Compress
    exit 0
}
if ($env:FUSION_RESTART_MODE -eq 'restart') {
    if ($null -eq $previous) { throw 'No Studio facade exists to restart' }
    Stop-Process -Id $previous -Force -ErrorAction Stop
}
if ($env:FUSION_RESTART_MODE -notin @('restart','ensure')) { throw 'Invalid restart mode' }
$arguments = @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',
               ('"{0}"' -f $env:FUSION_RESTART_SCRIPT))
$launcher = Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments `
    -WindowStyle Hidden -WorkingDirectory $env:FUSION_RESTART_DIRECTORY `
    -RedirectStandardOutput $env:FUSION_RESTART_STDOUT `
    -RedirectStandardError $env:FUSION_RESTART_STDERR -PassThru
@{ previousPid=$previous; launcherPid=$launcher.Id; launched=$true; verified=$true } | ConvertTo-Json -Compress
"""


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def progress(stage):
    print("STAGE " + stage, flush=True)
    return stage


def manage_facade(mode, run_id):
    check(os.name == "nt", "This restart smoke supports the local Windows workspace only")
    script = (ROOT / "run-studio-agent.ps1").resolve(strict=True)
    check(script.parent == ROOT and '"' not in str(script), "Unexpected Studio launcher path")
    logs = ROOT / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    environment = os.environ.copy()
    environment.update({
        "FUSION_RESTART_MODE": mode,
        "FUSION_RESTART_SCRIPT": str(script),
        "FUSION_RESTART_DIRECTORY": str(ROOT),
        "FUSION_RESTART_STDOUT": str(logs / ("shared-session-restart-" + run_id + "-" + mode + ".stdout.log")),
        "FUSION_RESTART_STDERR": str(logs / ("shared-session-restart-" + run_id + "-" + mode + ".stderr.log")),
    })
    control_stdout = logs / ("shared-session-restart-" + run_id + "-" + mode + ".control.stdout.log")
    control_stderr = logs / ("shared-session-restart-" + run_id + "-" + mode + ".control.stderr.log")
    # A detached Windows descendant can inherit PIPE handles. communicate()
    # then waits for descendant EOF even after killing the direct parent on
    # timeout. Files avoid those reader threads and bound this wait to the
    # short-lived controller; the facade keeps its separate service log files.
    with control_stdout.open("w", encoding="utf-8") as output, control_stderr.open("w", encoding="utf-8") as errors:
        result = subprocess.run(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", MANAGE_FACADE],
            env=environment, stdout=output, stderr=errors, stdin=subprocess.DEVNULL,
            timeout=30, check=False)
    check(result.returncode == 0, "Studio facade process verification/restart failed")
    return json.loads(control_stdout.read_text(encoding="utf-8-sig").strip())


def wait_ready(client, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            response = client.get("/health", timeout=max(0.01, min(0.5, deadline - time.monotonic())))
            if response.status_code == 200:
                return True
        except httpx.HTTPError:
            pass
        time.sleep(max(0, min(0.25, deadline - time.monotonic())))
    return False


def api(client, path, **body):
    response = client.post("/api/" + path, json=body)
    check(response.status_code == 200, "Unexpected HTTP status for " + path)
    result = response.json()
    check(result.get("code") == 200, "Business request failed for " + path)
    return result.get("data")


def run():
    run_id = uuid.uuid4().hex[:12]
    report = {"runId": run_id, "tenantId": "studio", "facadePort": 7051,
              "localOnly": True, "quantity": 1, "passed": False}
    stage = progress("preflight")
    cookie = None
    attempted_restart = False
    failure = None
    keys = None
    client = httpx.Client(base_url=BASE, timeout=8, trust_env=False)
    redis_url = os.getenv("FUSION_REDIS_URL", "redis://127.0.0.1:6379/0")
    shared = None
    try:
        check(urlparse(redis_url).hostname in {"127.0.0.1", "localhost", "::1"}, "This smoke requires local Redis")
        state = RedisSharedStore(ROOT / "runtime" / "store-studio.sqlite3", tenant="studio")
        shared = redis.Redis.from_url(redis_url, decode_responses=True,
                                     socket_connect_timeout=2, socket_timeout=2)
        check(wait_ready(client), "Studio facade is not ready before the test")
        owner = manage_facade("inspect", run_id)
        report["originalProcessId"] = owner["listenerPid"]
        check(shared.ping(), "Shared Redis is not available")

        stage = progress("create_shared_visitor_and_cart")
        api(client, "account/autoLogin")
        cookie = client.cookies.get(COOKIE)
        check(bool(cookie), "Studio login did not issue its session cookie")
        keys = state.keys(cookie)
        check(shared.exists(keys[0]) == 1, "Visitor session was not written to shared Redis")
        products = api(client, "product/loadProduct", pageSize=50)["list"]
        eligible = [row for row in products if float(row.get("stock", 0)) >= 1]
        check(bool(eligible), "No sellable Studio product is available for a one-unit cart")
        product = next((row for row in eligible if row.get("productCode") == "DEMO-LAMP-WIFI"), eligible[0])
        product_id = str(product["productId"])
        report["productId"] = product_id
        api(client, "productCart/add2Cart", productId=product_id, buyCount=1, propertyValueIds="default")
        before = api(client, "productCart/loadCart")["list"]
        check(len(before) == 1 and before[0]["buyCount"] == 1, "Fresh cart has an unexpected quantity")
        cart_id = before[0]["cartId"]
        check(json.loads(shared.hget(keys[2], product_id))["quantity"] == 1, "Cart was not written to shared Redis")

        stage = progress("restart_only_studio_facade")
        attempted_restart = True
        started = time.monotonic()
        restarted = manage_facade("restart", run_id)
        check(wait_ready(client), "Studio facade did not become ready within 20 seconds")
        after_owner = manage_facade("inspect", run_id)
        report["restartedProcessId"] = after_owner["listenerPid"]
        check(restarted["previousPid"] == owner["listenerPid"], "The listening facade changed before restart")
        check(after_owner["listenerPid"] != owner["listenerPid"], "Facade listener process did not change")
        report["restartSeconds"] = round(time.monotonic() - started, 3)

        stage = progress("verify_same_cookie_and_cart_after_restart")
        identity = api(client, "account/getUserInfo")
        check(identity.get("tenantId") == "studio", "Restarted facade returned a different tenant")
        check(client.cookies.get(COOKIE) == cookie, "Restart replaced the original visitor cookie")
        after = api(client, "productCart/loadCart")["list"]
        check(len(after) == 1 and after[0]["cartId"] == cart_id and after[0]["buyCount"] == 1,
              "Shared cart identity or quantity did not survive the facade restart")
        report.update(cookiePreserved=True, cartIdentityPreserved=True, cartQuantityPreserved=True)

        stage = progress("logout_and_replay_revoked_cookie")
        api(client, "account/logout")
        replay = client.post("/api/account/getUserInfo", json={}, headers={"Cookie": COOKIE + "=" + cookie})
        check(replay.json().get("code") == 901, "A revoked visitor cookie was accepted")
        check(shared.exists(keys[0], keys[2], keys[3]) == 0, "Logout retained active shared state")
        check(shared.get(keys[1]) == "revoked" and shared.ttl(keys[1]) > 0, "Logout did not persist a shared revocation marker")
        report.update(logoutRevoked=True, oldCookieRejected=True, sharedRevocationVerified=True, passed=True)
    except Exception as error:
        failure = error
        # Exception details can contain URLs/headers in third-party libraries.
        # Keep the report free of cookies and connection credentials.
        report.update(failureStage=stage, failureType=type(error).__name__)
    finally:
        service_available = wait_ready(client, timeout=2)
        if attempted_restart and not service_available:
            try:
                progress("recover_facade")
                manage_facade("ensure", run_id)
                service_available = wait_ready(client)
                report["recoveryAttempted"] = True
            except Exception as error:
                report["recoveryFailureType"] = type(error).__name__
        report["facadeAvailableAfterTest"] = service_available
        if cookie and service_available:
            try:
                cleanup = client.post("/api/account/logout", json={}, headers={"Cookie": COOKIE + "=" + cookie})
                report["testSessionCleaned"] = cleanup.json().get("code") in (200, 901)
            except Exception:
                report["testSessionCleaned"] = False
        if not service_available:
            report["passed"] = False
        REPORT.parent.mkdir(parents=True, exist_ok=True)
        REPORT.write_text(json.dumps(report, indent=2), encoding="utf-8")
        client.close()
        if shared is not None:
            shared.close()
    print(json.dumps(report, ensure_ascii=False), flush=True)
    if failure is not None or not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    run()
