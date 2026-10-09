"""Local business-API acceptance for reviewed supplier delay and cancellation.

Creates one fictional supplier commitment, delays it through independent review,
then cancels its outstanding unit through review. No SQL, physical stock posting,
supplier request or payment. All proposal/approval audit records are retained.
An interrupted run reports its owned IDs rather than deleting business evidence.
"""
from contextlib import ExitStack
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
from urllib.parse import urlparse

from smoke_supply_execution import Acceptance, JAVA, SafeFailure


class SupplierExceptions(Acceptance):
    def __init__(self):
        super().__init__()
        self.report.update({"kind": "REVIEWED_SUPPLIER_EXCEPTION", "quantity": 1,
                            "stockPosted": False, "paymentCalled": False})

    def facts(self, admin, pid, name):
        return next(row for row in self.api(admin, name, "GET", "/commerce/planning/replenishment")["items"]
                    if int(row["productId"]) == pid)

    def run(self):
        if urlparse(JAVA).hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise SafeFailure("Supplier exception verification is limited to local services")
        with ExitStack() as stack:
            admin = self.merchant(stack, os.getenv("FUSION_SMOKE_USER", "admin"))
            reviewer = self.merchant(stack, "demo_supply_reviewer")
            other_tenant = self.merchant(stack, "studio_admin")
            identities = [self.api(client, name, "GET", "/commerce/context") for client, name in
                          [(admin, "ownerContext"), (reviewer, "reviewerContext"), (other_tenant, "otherTenantContext")]]
            self.check("independentIdentities", identities[0]["tenantId"] == identities[1]["tenantId"] == "demo"
                       and identities[2]["tenantId"] == "studio" and identities[0]["userId"] != identities[1]["userId"])
            rows = self.api(admin, "currentReplenishment", "GET", "/commerce/planning/replenishment")["items"]
            candidates = [row for row in rows if row["suggestedQuantity"] >= 1 and str(row["productCode"]).startswith("LAB-")]
            self.check("existingSimulatedSkuHasUncommittedNeed", bool(candidates))
            selected = sorted(candidates, key=lambda row: (row["productCode"] != "LAB-TAPO-T300", row["productId"]))[0]
            pid = int(selected["productId"])
            self.report.update({"productId": pid, "productCode": selected["productCode"]})
            before_stock = self.balances(self.stock(admin, pid, "stockBefore"))
            before_ledger = self.api(admin, "stockLedgerBefore", "GET", f"/commerce/inventory/{pid}/ledger")
            warehouses = self.request(admin, "existingWarehouses", "GET", "/baseDate/warehouse/list?pageSize=500")["rows"]
            self.check("existingWarehouseAvailable", bool(warehouses))
            warehouse = int(warehouses[0]["warehouseId"])
            prefix = "exception_" + self.run_id
            created = self.api(admin, "createOwnDraft", "POST", "/commerce/planning/drafts",
                               {"requestKey": prefix, "items": [{"productId": pid, "quantity": 1}]})
            did = created["draftId"]
            self.report["state"]["draftId"] = did
            approve = {"requestKey": prefix + "_draft_review", "decision": "APPROVE",
                       "note": "虚构供货异常验收：独立核验一件采购承诺，最后取消剩余，不入库不付款"}
            self.api(reviewer, "independentDraftApproval", "POST", f"/commerce/planning/drafts/{did}/review", approve)
            original_at = (datetime.now().replace(microsecond=0) + timedelta(days=2)).isoformat()
            executed = self.api(admin, "fictionalSupplierConfirmation", "POST", f"/commerce/planning/drafts/{did}/execute",
                                {"requestKey": prefix + "_exec", "warehouseId": warehouse,
                                 "sourceReference": "虚构供应异常确认-" + self.run_id, "expectedAt": original_at})
            iid = executed["supplyLines"][0]["incomingId"]
            self.report["state"]["incomingId"] = iid
            self.check("committedOneUnit", self.facts(admin, pid, "afterConfirmation")["committedSupplyQuantity"]
                       == selected["committedSupplyQuantity"] + 1)
            delayed_at = (datetime.now().replace(microsecond=0) + timedelta(days=4)).isoformat()
            delay_body = {"requestKey": prefix + "_delay", "action": "DELAY", "expectedAt": delayed_at,
                          "reason": "虚构供应商通知延期到货，保留原采购承诺", "sourceReference": "虚构延期通知-" + self.run_id}
            delay = self.api(admin, "delayProposal", "POST", f"/commerce/planning/incoming/{iid}/changes", delay_body)
            delay_id = delay["changeId"]
            self.report["state"]["delayChangeId"] = delay_id
            self.check("delayReplaySameProposal", self.api(admin, "delayProposalReplay", "POST",
                       f"/commerce/planning/incoming/{iid}/changes", delay_body) == delay)
            review_delay = {"requestKey": prefix + "_delay_review", "decision": "APPROVE", "note": "独立核验虚构延期通知"}
            self.api(admin, "delaySelfReviewDenied", "POST", f"/commerce/planning/incoming/changes/{delay_id}/review", review_delay, 403)
            applied = self.api(reviewer, "delayIndependentApproval", "POST", f"/commerce/planning/incoming/changes/{delay_id}/review", review_delay)
            self.check("delayAppliedWithOriginalPromise", applied["status"] == "APPLIED"
                       and applied["before"]["expectedAt"] == original_at and applied["after"]["expectedAt"] == delayed_at)
            self.check("delayDoesNotReleaseCommitment", self.facts(admin, pid, "afterDelay")["committedSupplyQuantity"]
                       == selected["committedSupplyQuantity"] + 1)
            self.check("delayApprovalReplay", self.api(reviewer, "delayApprovalReplay", "POST",
                       f"/commerce/planning/incoming/changes/{delay_id}/review", review_delay) == applied)
            self.api(reviewer, "delayChangedReviewDenied", "POST", f"/commerce/planning/incoming/changes/{delay_id}/review",
                     {**review_delay, "note": "changed-note"}, 409)
            cancel_body = {"requestKey": prefix + "_cancel", "action": "CANCEL_REMAINDER",
                           "reason": "虚构供应商确认剩余一件无法交付，取消后重新计算需求",
                           "sourceReference": "虚构取消剩余确认-" + self.run_id}
            cancellation = self.api(admin, "cancelProposal", "POST", f"/commerce/planning/incoming/{iid}/changes", cancel_body)
            cid = cancellation["changeId"]
            self.report["state"]["cancellationChangeId"] = cid
            self.check("cancelProposalDoesNotReleaseBeforeApproval", self.facts(admin, pid, "beforeCancelApproval")["committedSupplyQuantity"]
                       == selected["committedSupplyQuantity"] + 1)
            self.api(admin, "sameKeyChangedReasonDenied", "POST", f"/commerce/planning/incoming/{iid}/changes",
                     {**cancel_body, "reason": "changed-reason"}, 409)
            self.api(other_tenant, "foreignTenantProposalDenied", "POST", f"/commerce/planning/incoming/{iid}/changes", cancel_body, 404)
            review_cancel = {"requestKey": prefix + "_cancel_review", "decision": "APPROVE", "note": "独立核验虚构剩余取消依据"}
            self.api(admin, "cancelSelfReviewDenied", "POST", f"/commerce/planning/incoming/changes/{cid}/review", review_cancel, 403)
            self.api(other_tenant, "foreignTenantReviewDenied", "POST", f"/commerce/planning/incoming/changes/{cid}/review", review_cancel, 404)
            closed = self.api(reviewer, "cancelIndependentApproval", "POST", f"/commerce/planning/incoming/changes/{cid}/review", review_cancel)
            self.check("cancelAppliedOnlyUnreceivedQuantity", closed["status"] == "APPLIED"
                       and closed["after"]["quantity"] == 1 and closed["after"]["receivedQuantity"] == 0
                       and closed["after"]["cancelledQuantity"] == 1 and closed["after"]["outstandingQuantity"] == 0)
            self.check("cancelApprovalReplay", self.api(reviewer, "cancelApprovalReplay", "POST",
                       f"/commerce/planning/incoming/changes/{cid}/review", review_cancel) == closed)
            row = next(row for row in self.api(admin, "incomingFinal", "GET", "/commerce/planning/incoming") if row["incomingId"] == iid)
            self.check("batchClosedWithOriginalQuantity", row["status"] == "CANCELLED" and row["quantity"] == 1
                       and row["receivedQuantity"] == 0 and row["cancelledQuantity"] == 1 and row["outstandingQuantity"] == 0)
            final = next(row for row in self.drafts(admin, "draftFinal") if row["draftId"] == did)
            self.check("draftAndLineCloseTogether", final["status"] == "CANCELLED" and final["executionStatus"] == "SUPPLIER_CANCELLED"
                       and final["supplyLines"][0]["state"] == "CANCELLED")
            facts = self.facts(admin, pid, "replenishmentFinal")
            self.check("originalUncommittedNeedRestored", facts["committedSupplyQuantity"] == selected["committedSupplyQuantity"]
                       and facts["suggestedQuantity"] == selected["suggestedQuantity"])
            self.check("physicalStockUnchanged", self.balances(self.stock(admin, pid, "stockFinal")) == before_stock)
            self.check("stockLedgerUnchanged", self.api(admin, "stockLedgerFinal", "GET", f"/commerce/inventory/{pid}/ledger") == before_ledger)
            audit = [row for row in self.api(admin, "retainedChangeAudit", "GET", "/commerce/planning/incoming/changes") if row["incomingId"] == iid]
            self.check("twoIndependentChangesRetained", len(audit) == 2 and all(row["status"] == "APPLIED"
                       and row["createdBy"] != row["reviewedBy"] and row["reason"] and row["sourceReference"] for row in audit))
            self.report["state"].update({"draftStatus": final["status"], "incomingStatus": row["status"]})
            self.report["success"] = True


if __name__ == "__main__":
    acceptance = SupplierExceptions()
    try:
        acceptance.run()
    except Exception as failure:
        acceptance.report["success"] = False
        acceptance.report["failure"] = type(failure).__name__ + ": " + str(failure)[:200]
    finally:
        acceptance.report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        target = Path(__file__).resolve().parent.parent / "logs" / "supply-exceptions-result.json"
        target.write_text(json.dumps(acceptance.report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"success": acceptance.report.get("success", False), "checks": len(acceptance.report["checks"]),
                          "failedChecks": [row["name"] for row in acceptance.report["checks"] if not row["passed"]],
                          "report": str(target)}, ensure_ascii=False))
    raise SystemExit(0 if acceptance.report.get("success") else 1)
