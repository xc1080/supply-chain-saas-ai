"""Two-phase HTTP fault evidence. The orchestration owns process restart and SQL.

The private state file contains this run's visitor cookie and never becomes an
artifact. Orders, stock and payment facts are created through the actual API.
"""
from contextlib import ExitStack
import argparse
import json
import os
from pathlib import Path
import sys
import time

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "fusion-agent"))
from smoke_closing_business import ClosingBusiness
from smoke_supply_execution import JAVA, STORE


def prepare(state_path, report_path):
    scenario = ClosingBusiness()
    with ExitStack() as stack:
        admin = scenario.merchant(stack, os.getenv("FUSION_SMOKE_USER", "admin"))
        buyer = scenario.visitor(stack)
        identity = scenario.api(admin, "recovery.context", "GET", "/commerce/context")
        buyer_identity = scenario.call(buyer, "recovery.visitor", "account/getUserInfo")
        # These IDs are created and checked by the isolated baseline installer.
        pid, body = scenario.create_product(admin, "RECOVERY", 49, 2, 1)
        scenario.purchase(admin, "RECOVERY", pid, 3, body, 1, identity["userId"])
        address = scenario.call(buyer, "recovery.address", "userAddress/addAddress", addressee="恢复验收访客", phone="00000000000",
                                address="虚构隔离恢复测试地址", defaultType=1)["addressId"]
        bodies = {name: {"payMethod": "demo", "addressId": address, "clientRequestId": "recover_" + name + "_" + scenario.run_id,
                         "orderFrom": "1", "orderList": [{"productId": str(pid), "buyCount": 1, "price": .01}]}
                  for name in ("unpaid", "charged")}
        orders = {name: scenario.call(buyer, "recovery." + name + ".create", "order/postOrder", **body)["orderId"]
                  for name, body in bodies.items()}
        held = scenario.balances(scenario.stock(admin, pid, "recovery.stockHeld"))
        scenario.check("twoDistinctOwnedHolds", orders["charged"] != orders["unpaid"] and held["bookStock"] == 3 and held["reservedStock"] == 2)
        payment = {"orderId": orders["charged"], "paymentRequestId": "recover_pay_" + scenario.run_id, "scenario": "timeout_after_success"}
        # Keep this last: the orchestrator interrupts only its own Java quickly,
        # before the normal five-second reconciliation retry becomes eligible.
        result = scenario.call(buyer, "recovery.timeoutAfterProviderSuccess", "order/sandboxPay", **payment)
        operation = result["paymentOperation"]
        scenario.check("providerChargedButLocalResultUnknown", result["orderStatus"] == 0 and result["paymentOutcome"] == "UNKNOWN"
                       and operation["providerStatus"] == "SUCCEEDED" and operation["provider"] == "LOCAL_SANDBOX")
        state = {"runId": scenario.run_id, "cookies": dict(buyer.cookies), "visitorId": buyer_identity["userId"],
                 "productId": pid, "orders": orders, "bodies": bodies, "payment": payment, "operation": operation,
                 "heldStock": held, "preparedAt": time.time()}
        state_path.write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")
        report_path.write_text(json.dumps({"status": "PREPARED", "passed": False, "checks": scenario.report["checks"],
                                          "productId": pid, "orders": orders, "operationBeforeRestart": operation}, ensure_ascii=False, indent=2, default=str), encoding="utf-8")


def verify(state_path, report_path):
    state = json.loads(state_path.read_text(encoding="utf-8"))
    scenario = ClosingBusiness()
    report = json.loads(report_path.read_text(encoding="utf-8"))
    try:
        with ExitStack() as stack:
            admin = scenario.merchant(stack, os.getenv("FUSION_SMOKE_USER", "admin"))
            buyer = stack.enter_context(httpx.Client(base_url=STORE, timeout=45, trust_env=False))
            buyer.cookies.update(state["cookies"])
            identity = scenario.call(buyer, "recovery.sameVisitorAfterRestart", "account/getUserInfo")
            scenario.check("samePrivateVisitorSessionSurvivesRestart", identity["userId"] == state["visitorId"])
            queried_order = scenario.call(buyer, "recovery.queryOriginalOperation", "order/querySandboxPayment",
                                          orderId=state["orders"]["charged"], operationId=state["operation"]["operationId"])
            query = queried_order["paymentOperation"]
            scenario.check("originalOperationRecovered", query["operationId"] == state["operation"]["operationId"]
                           and query["outcome"] == "SUCCEEDED" and query["providerStatus"] == "SUCCEEDED")
            replay = scenario.call(buyer, "recovery.paymentReplay", "order/sandboxPay", **state["payment"])
            scenario.check("samePaymentReplayUsesOriginalOperation", replay["orderStatus"] == 1 and replay["paymentOutcome"] == "SUCCEEDED"
                           and replay["paymentOperation"]["operationId"] == query["operationId"])
            for name in ("charged", "unpaid"):
                replayed_order = scenario.call(buyer, "recovery." + name + ".orderReplay", "order/postOrder", **state["bodies"][name])
                scenario.check("recovery." + name + ".originalOrderReplay", replayed_order["orderId"] == state["orders"][name])
            deadline = time.monotonic() + 90
            while True:
                expired = scenario.call(buyer, "recovery.waitSchedulerExpiry", "order/getMyOrderDetail", orderId=state["orders"]["unpaid"])
                if expired["orderStatus"] == 4:
                    break
                scenario.check("unpaidSchedulerRecoveryWithinDeadline", time.monotonic() < deadline)
                time.sleep(.5)
            scenario.check("unpaidOrderReleasedByNormalScheduler", expired["closeReason"] == "PAYMENT_TIMEOUT")
            final = scenario.balances(scenario.stock(admin, state["productId"], "recovery.finalStock"))
            scenario.check("onePaidHoldAndOneTimeoutRelease", final["bookStock"] == 3 and final["reservedStock"] == 1
                           and final["availableStock"] == 2 and final["activityStock"] == 0 and final["unavailableStock"] == 0)
            other = scenario.visitor(stack)
            scenario.call(other, "recovery.foreignVisitorCannotRead", "order/getMyOrderDetail", expected=404, orderId=state["orders"]["charged"])
            report.update(passed=True, status="PASSED", recoveredOperation=query, finalStock=final,
                          visitorCookieStoredOnlyPrivately=True, sqliteAndRedisRetainedAcrossRestart=True)
    except BaseException as error:
        report.update(passed=False, status="FAILED", errorType=type(error).__name__)
        raise
    finally:
        report["checks"].extend(scenario.report["checks"])
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("prepare", "verify"))
    parser.add_argument("--private-state", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    if args.private_state.resolve().is_relative_to(args.report.resolve().parent):
        raise SystemExit("Visitor state must stay outside the public artifact directory")
    (prepare if args.phase == "prepare" else verify)(args.private_state, args.report)
