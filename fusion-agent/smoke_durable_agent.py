"""Local durable procurement acceptance, preserving all business audit records.

Default: create -> approve -> change supply -> invalidate -> reapprove -> execute,
discard the result, reconnect, replay the exact command, and cancel only owned
fictional supplier batches. No SQL, physical receipt, supplier call or payment.

For an actual server restart, run --prepare, restart Java/Python externally, then
run --resume. The report persists IDs and the original business command, never
credentials or a bearer. --resume reauthenticates and closes owned commitments.
"""
from __future__ import annotations

import argparse
from contextlib import ExitStack
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
from urllib.parse import urlparse
import httpx

from smoke_supply_execution import Acceptance, JAVA, SafeFailure


REPORT = Path(__file__).resolve().parent.parent / "logs" / "durable-agent-result.json"
AGENT = os.getenv("FUSION_SMOKE_AGENT", "http://127.0.0.1:7050").rstrip("/")


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()


class DurableProcurement(Acceptance):
    def __init__(self, saved=None):
        super().__init__()
        if saved is not None:
            if not isinstance(saved, dict) or saved.get("kind") != "DURABLE_PROCUREMENT_TASK" or saved.get("stage") != "AWAITING_RESTART_RESUME":
                raise SafeFailure("Report does not contain a prepared durable task")
            self.report = saved
            self.run_id = saved["runId"]
        self.report.update({"kind": "DURABLE_PROCUREMENT_TASK", "stockPosted": False,
                            "paymentCalled": False, "sqlUsed": False, "modelCalled": False,
                            "plannerMode": "rules", "auditDocumentsRetained": True})

    def local_only(self):
        if urlparse(JAVA).hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise SafeFailure("Durable task verification is restricted to local services")

    def facts(self, admin, name):
        pid = self.report["productId"]
        return next(row for row in self.api(admin, name, "GET", "/commerce/planning/replenishment")["items"]
                    if int(row["productId"]) == pid)

    def prepare(self):
        self.local_only()
        with ExitStack() as stack:
            admin = self.merchant(stack, os.getenv("FUSION_SMOKE_USER", "admin"))
            reviewer = self.merchant(stack, "demo_supply_reviewer")
            foreign = self.merchant(stack, "studio_admin")
            identity = self.api(admin, "ownerContext", "GET", "/commerce/context")
            reviewer_identity = self.api(reviewer, "reviewerContext", "GET", "/commerce/context")
            self.check("sameTenantIndependentReviewer", identity["tenantId"] == reviewer_identity["tenantId"] == "demo"
                       and identity["userId"] != reviewer_identity["userId"])
            rows = self.api(admin, "currentReplenishment", "GET", "/commerce/planning/replenishment")["items"]
            candidates = [row for row in rows if str(row["productCode"]).startswith("LAB-")
                          and 2 <= int(row["suggestedQuantity"]) <= 999]
            self.check("existingSimulatedSkuHasAtLeastTwoUnitsNeed", bool(candidates))
            selected = sorted(candidates, key=lambda row: (int(row["suggestedQuantity"]), int(row["productId"])))[0]
            pid, quantity = int(selected["productId"]), int(selected["suggestedQuantity"])
            self.report.update({"productId": pid, "productCode": selected["productCode"], "quantity": quantity,
                                "baselineFacts": {field: selected[field] for field in ("suggestedQuantity", "committedSupplyQuantity")}})
            self.report["stockBefore"] = self.balances(self.stock(admin, pid, "stockBefore"))
            self.report["ledgerBeforeHash"] = fingerprint(self.api(admin, "ledgerBefore", "GET", f"/commerce/inventory/{pid}/ledger"))
            warehouses = self.request(admin, "existingWarehouses", "GET", "/baseDate/warehouse/list?pageSize=500")["rows"]
            self.check("existingWarehouseFound", bool(warehouses));warehouse = int(warehouses[0]["warehouseId"])
            prefix = "durable_" + self.run_id
            create = {"requestKey": prefix, "goal": "学习验收：按真实缺口形成虚构采购任务，供应改变后重审批，不付款不入库",
                      "plannerMode": "rules", "items": [{"productId": pid, "quantity": quantity}]}
            task = self.api(admin, "createTask", "POST", "/commerce/agent-tasks", create)
            tid = task["taskId"];self.report["state"]["taskId"] = tid
            self.check("newTaskWaitsForApproval", task["status"] == "WAITING_APPROVAL" and task["planVersion"] == 1
                       and task["createdBy"] == identity["userId"] and task["shopId"] == "default")
            self.check("createReplaySameTask", self.api(admin, "createTaskReplay", "POST", "/commerce/agent-tasks", create)["taskId"] == tid)
            self.api(admin, "changedTaskGoalDenied", "POST", "/commerce/agent-tasks", {**create, "goal": "changed goal"}, 409)
            self.api(foreign, "foreignTenantCannotRead", "GET", f"/commerce/agent-tasks/{tid}", expected=404)
            self.check("waitingTaskDoesNotReserveProcurement", self.facts(admin, "factsBeforeApproval")["committedSupplyQuantity"] == selected["committedSupplyQuantity"])
            for namespace in ("BTPROP:", "btprop:", "BTREVIEW:", "BTEXEC:"):
                self.api(admin, "reservedDraftKeyDenied." + namespace, "POST", "/commerce/planning/drafts",
                         {"requestKey": namespace + tid + ":1", "items": [{"productId": pid, "quantity": 1}]}, 400)
            first_review = {"requestKey": prefix + "_review_v1", "planVersion": 1, "decision": "APPROVE",
                            "note": "独立核验虚构采购任务；后续制造事实变化验证旧审批失效"}
            self.api(admin, "selfApprovalDenied", "POST", f"/commerce/agent-tasks/{tid}/review", first_review, 403)
            approved = self.api(reviewer, "independentApprovalV1", "POST", f"/commerce/agent-tasks/{tid}/review", first_review)
            self.check("taskLinksActualIndependentlyApprovedDraft", approved["status"] == "APPROVED"
                       and approved["draftId"] and approved["approvedBy"] == reviewer_identity["userId"])
            old_draft = approved["draftId"];self.report["state"]["invalidatedDraftId"] = old_draft
            supplied_at = (datetime.now().replace(microsecond=0) + timedelta(days=3)).isoformat()
            first_execution = {"requestKey": prefix + "_execution_v1", "planVersion": 1, "warehouseId": warehouse,
                               "sourceReference": "虚构任务确认-" + self.run_id, "expectedAt": supplied_at}
            self.api(admin, "legacyDraftExecuteCannotBypassTask", "POST", f"/commerce/planning/drafts/{old_draft}/execute",
                     {key: value for key, value in first_execution.items() if key != "planVersion"}, 409)
            self.api(admin, "reservedIncomingKeyDenied", "POST", "/commerce/planning/incoming",
                     {"requestKey": "DRAFT:" + old_draft + ":" + str(pid), "productId": pid, "quantity": 1,
                      "warehouseId": warehouse, "sourceReference": "保留键禁止预占", "expectedAt": supplied_at}, 400)
            incoming = self.api(admin, "separateFictionalSupplyChangesFacts", "POST", "/commerce/planning/incoming",
                                {"requestKey": prefix + "_external_supply", "productId": pid, "warehouseId": warehouse,
                                 "quantity": 1, "sourceReference": "虚构独立供货改变采购缺口-" + self.run_id, "expectedAt": supplied_at})
            self.report["state"]["extraIncomingId"] = incoming["incomingId"]
            stale = self.api(admin, "staleExecuteCommitsReplanning", "POST", f"/commerce/agent-tasks/{tid}/execute", first_execution)
            self.check("oldApprovalInvalidatedAndNewVersionPersists", stale["replanRequired"] is True
                       and stale["status"] == "WAITING_APPROVAL" and stale["planVersion"] == 2
                       and stale["approvedBy"] is None and stale["draftId"] is None
                       and stale["items"][0]["quantity"] == quantity - 1)
            drafts = self.api(admin, "oldDraftAuditRetained", "GET", "/commerce/planning/drafts")
            self.check("oldCommitmentCancelledWithoutDeletingAudit", next(row for row in drafts if row["draftId"] == old_draft)["status"] == "CANCELLED")
            self.api(reviewer, "oldPlanVersionCannotBeApproved", "POST", f"/commerce/agent-tasks/{tid}/review",
                     {**first_review, "requestKey": prefix + "_obsolete_review"}, 409)
            refreshed = self.api(admin, "explicitResumeUsesCurrentFacts", "POST", f"/commerce/agent-tasks/{tid}/refresh",
                                 {"requestKey": prefix + "_resume"})
            self.check("unchangedResumeDoesNotInventNewVersion", refreshed["planVersion"] == 2 and refreshed["replanRequired"] is False)
            second_review = {"requestKey": prefix + "_review_v2", "planVersion": 2, "decision": "APPROVE",
                             "note": "独立核验新供货抵扣后的版本2，登记确认后验收取消剩余承诺"}
            approved = self.api(reviewer, "independentApprovalV2", "POST", f"/commerce/agent-tasks/{tid}/review", second_review)
            self.report["state"]["executedDraftId"] = approved["draftId"]
            self.check("v2HasNewActualDraftAndApproval", approved["status"] == "APPROVED"
                       and approved["draftId"] != old_draft and approved["approvedVersion"] == 2)
            command = {**first_execution, "requestKey": prefix + "_stable_execution_v2", "planVersion": 2}
            self.report["state"]["executionCommand"] = command
            # Intentionally discard this business result. Recovery uses persisted Java state.
            self.api(admin, "executeV2DiscardReturnedBusinessResult", "POST", f"/commerce/agent-tasks/{tid}/execute", command)
            self.report["stage"] = "AWAITING_RESTART_RESUME"
            self.report["prepared"] = True

    def close_owned(self, admin, reviewer):
        state = self.report["state"]
        tid = state.get("taskId")
        if tid:
            task = self.api(admin, "cleanupReadOwnTask", "GET", f"/commerce/agent-tasks/{tid}")
            if task["status"] in {"WAITING_APPROVAL", "APPROVED"}:
                self.api(admin, "cleanupCancelOnlyOwnUnexecutedTask", "POST", f"/commerce/agent-tasks/{tid}/cancel",
                         {"requestKey": "durable_cleanup_task_" + self.run_id, "reason": "学习验收结束，撤销仅本次创建的未执行承诺"})
            if task.get("result"):
                state["executedIncomingIds"] = [line["incomingId"] for line in task["result"]["supplyLines"]]
        ids = [state.get("extraIncomingId"), *state.get("executedIncomingIds", [])]
        for index, iid in enumerate(dict.fromkeys(value for value in ids if value)):
            incoming = next((row for row in self.api(admin, "cleanupIncomingList." + str(index), "GET", "/commerce/planning/incoming")
                             if row["incomingId"] == iid), None)
            self.check("ownedIncomingFound." + str(index), incoming is not None)
            if incoming["outstandingQuantity"] == 0:
                continue
            change = self.api(admin, "cancelOwnFictionalRemainder." + str(index), "POST", f"/commerce/planning/incoming/{iid}/changes",
                              {"requestKey": "durable_cleanup_supply_" + self.run_id + "_" + str(index), "action": "CANCEL_REMAINDER",
                               "reason": "学习验收结束，取消本次虚构供应确认剩余，不变更实物库存",
                               "sourceReference": "虚构验收取消确认-" + self.run_id + "-" + str(index)})
            self.api(reviewer, "independentCleanupApproval." + str(index), "POST", f"/commerce/planning/incoming/changes/{change['changeId']}/review",
                     {"requestKey": "durable_cleanup_review_" + self.run_id + "_" + str(index), "decision": "APPROVE",
                      "note": "独立核验仅本次验收创建的供货承诺，取消未收数量，审计保留"})

    def resume(self, externally_restarted=False):
        self.local_only()
        with ExitStack() as stack:
            admin = self.merchant(stack, os.getenv("FUSION_SMOKE_USER", "admin"))
            reviewer = self.merchant(stack, "demo_supply_reviewer")
            tid = self.report["state"]["taskId"]
            try:
                current = self.api(admin, "freshSessionReadsPersistedTask", "GET", f"/commerce/agent-tasks/{tid}")
                self.check("responseLossTaskAlreadyCommitted", current["status"] == "EXECUTED" and current["planVersion"] == 2)
                self.check("bothBusinessVersionsRetained", len(current["versions"]) == 2)
                self.check("auditIncludesReplanAndIndependentReview", {row["action"] for row in current["events"]}
                           >= {"CREATED", "APPROVED", "REPLANNED", "EXECUTED"})
                self.report["state"]["executedIncomingIds"] = [line["incomingId"] for line in current["result"]["supplyLines"]]
                self.check("oneIncomingForThisTask", len(self.report["state"]["executedIncomingIds"]) == 1)
                incoming_before = [row["incomingId"] for row in self.api(admin, "incomingIdsBeforeRecovery", "GET", "/commerce/planning/incoming")]
                replay = self.api(admin, "sameBusinessCommandAfterReconnectOrRestart", "POST", f"/commerce/agent-tasks/{tid}/execute",
                                  self.report["state"]["executionCommand"])
                self.check("exactReplayReturnsOriginalBusinessResult", replay["result"] == current["result"])
                incoming_after = [row["incomingId"] for row in self.api(admin, "incomingIdsAfterRecovery", "GET", "/commerce/planning/incoming")]
                self.check("recoveryCreatesNoSecondIncoming", incoming_after == incoming_before)
                self.api(admin, "changedExecutionKeyDenied", "POST", f"/commerce/agent-tasks/{tid}/execute",
                         {**self.report["state"]["executionCommand"], "requestKey": "changed_" + self.run_id}, 409)
                self.report["externallyRestartedBeforeResume"] = bool(externally_restarted)
            finally:
                self.close_owned(admin, reviewer)
            final = self.facts(admin, "factsAfterOwnedPromisesClosed")
            self.check("procurementNeedAndCommitmentsRestored", all(final[field] == expected for field, expected in self.report["baselineFacts"].items()))
            self.check("physicalBalancesUnchanged", self.balances(self.stock(admin, self.report["productId"], "stockFinal")) == self.report["stockBefore"])
            self.check("noInventoryLedgerPosted", fingerprint(self.api(admin, "ledgerFinal", "GET", f"/commerce/inventory/{self.report['productId']}/ledger")) == self.report["ledgerBeforeHash"])
            self.check("executedTaskHistoryStillRetained", self.api(admin, "retainedTask", "GET", f"/commerce/agent-tasks/{tid}")["status"] == "EXECUTED")
            self.report.update({"stage": "COMPLETE", "success": True})

    def cleanup_after_failure(self):
        if not self.report["state"].get("taskId"):
            return
        with ExitStack() as stack:
            admin = self.merchant(stack, os.getenv("FUSION_SMOKE_USER", "admin"))
            reviewer = self.merchant(stack, "demo_supply_reviewer")
            self.close_owned(admin, reviewer)

    def model_proposal(self):
        """Use the real Python facade, retain the model task, then cancel it unapproved."""
        self.local_only()
        if urlparse(AGENT).hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise SafeFailure("Model task verification is restricted to local services")
        with ExitStack() as stack:
            admin = self.merchant(stack, os.getenv("FUSION_SMOKE_USER", "admin"))
            agent = stack.enter_context(httpx.Client(base_url=AGENT, timeout=115, trust_env=False))
            # Forward the current human bearer only in memory; the report never stores it.
            agent.headers["Authorization"] = admin.headers["Authorization"]
            request_key = "durable_model_" + self.run_id
            code, pid = self.report["productCode"], self.report["productId"]
            goal = f"仅为商品编码 {code} 创建补货采购任务，最多采购2件，只选择此商品，不选择任何其他商品；等待独立审批。"
            model_task = None
            try:
                response = agent.post("/business/tasks/procurement", json={"shopId": "default", "requestKey": request_key, "goal": goal})
                self.check("realModelFacadeResponse", response.status_code == 200)
                model_task = response.json()
                self.report["state"]["modelTaskId"] = model_task["taskId"]
                self.report.update({"modelCalled": True, "modelPlannerMode": model_task["plannerMode"],
                                    "modelPlannerRunId": model_task.get("plannerRunId")})
                self.check("persistedTaskUsesRealModelProposal", model_task["plannerMode"] == "model"
                           and bool(model_task.get("plannerRunId")) and model_task["status"] == "WAITING_APPROVAL")
                items = model_task.get("requestedItems")
                self.check("modelChoseOnlyRequestedSkuAndAtMostTwo", isinstance(items, list) and len(items) == 1
                           and int(items[0]["productId"]) == pid and 1 <= int(items[0]["quantity"]) <= 2)
                self.check("modelTaskCreatedNoDraftApprovalOrExecution", model_task.get("draftId") is None
                           and model_task.get("approvedBy") is None and model_task.get("result") is None)
            finally:
                # Even an unwanted model proposal is cancelled, never reviewed or executed.
                # If the HTTP response was lost, locate it using the stable business key.
                if model_task is None:
                    response = admin.get("/commerce/agent-tasks/request/" + request_key)
                    payload = response.json()
                    if payload.get("code") == 200:
                        model_task = payload["data"]
                        self.report["state"]["modelTaskId"] = model_task["taskId"]
                if model_task is not None:
                    cancelled = self.api(admin, "cancelOnlyModelProposedUnapprovedTask", "POST",
                                         "/commerce/agent-tasks/" + model_task["taskId"] + "/cancel",
                                         {"requestKey": "durable_model_cancel_" + self.run_id,
                                          "reason": "真实模型采购提案验收结束，保留任务审计，不审批不执行任何商品"})
                    self.check("modelSampleRetainedAsCancelledTask", cancelled["status"] == "CANCELLED")
                self.check("modelNeverChangedInventory", self.balances(self.stock(admin, pid, "modelStockFinal")) == self.report["stockBefore"])
                self.check("modelNeverPostedInventoryLedger", fingerprint(self.api(admin, "modelLedgerFinal", "GET",
                           f"/commerce/inventory/{pid}/ledger")) == self.report["ledgerBeforeHash"])


def main():
    args = argparse.ArgumentParser()
    args.add_argument("--prepare", action="store_true", help="Persist the task and command, then pause for an external server restart")
    args.add_argument("--resume", action="store_true", help="Reauthenticate, recover the prepared task, and close only owned supplier promises")
    args.add_argument("--with-model", action="store_true", help="After the complete rules lifecycle, persist and cancel a real bounded model proposal")
    options = args.parse_args()
    if options.prepare and options.resume:
        args.error("Choose either --prepare or --resume")
    if options.with_model and (options.prepare or options.resume):
        args.error("--with-model runs only with the complete default lifecycle")
    acceptance = DurableProcurement(json.loads(REPORT.read_text(encoding="utf-8")) if options.resume else None)
    try:
        if options.resume:
            acceptance.resume(externally_restarted=True)
        else:
            acceptance.prepare()
            if options.prepare:
                acceptance.report["success"] = True
            else:
                acceptance.resume()
                if options.with_model:
                    acceptance.model_proposal()
    except Exception as failure:
        acceptance.report.update({"success": False, "failure": type(failure).__name__ + ": " + str(failure)[:160]})
        try:
            acceptance.cleanup_after_failure()
        except Exception as cleanup_failure:
            acceptance.report["cleanupFailure"] = type(cleanup_failure).__name__ + ": " + str(cleanup_failure)[:160]
    finally:
        acceptance.report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        REPORT.write_text(json.dumps(acceptance.report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"success": acceptance.report.get("success", False), "stage": acceptance.report.get("stage"),
                          "checks": len(acceptance.report["checks"]),
                          "failedChecks": [row["name"] for row in acceptance.report["checks"] if not row["passed"]],
                          "report": str(REPORT)}, ensure_ascii=False))
    return 0 if acceptance.report.get("success") else 1


if __name__ == "__main__":raise SystemExit(main())
