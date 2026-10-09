"""Verify the live local customer proof and merchant authorization boundaries.

Credentials and bearer tokens stay in memory. The only permitted write is an
unexecuted procurement draft selected from current replenishment facts, followed
by independent review and business-API cancellation. No SQL or stock posting.
"""
from __future__ import annotations

from contextlib import ExitStack
from datetime import datetime, timezone
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import sys
import time
from urllib.parse import urlparse

import httpx


BASE = os.getenv("FUSION_JAVA_URL", "http://127.0.0.1:8035").rstrip("/")
ROOT = Path(__file__).resolve().parent.parent
CREDENTIALS_PATH = ROOT / "runtime" / "commerce-service-credentials.json"
RESULT_PATH = ROOT / "logs" / "engineering-boundaries-result.json"
OWNER_CAPABILITIES = {
    "READ", "CATALOG", "FULFILMENT", "REFUND_REVIEW", "REFUND_EXECUTE",
    "STOCK_ADJUST", "SUPPLY_POLICY", "SUPPLY_DRAFT", "SUPPLY_REVIEW", "SHOP_MEMBERS",
}
PUBLIC_FIELDS = {
    "productId", "productCode", "productName", "cover", "spec", "price",
    "description", "categoryName", "productStatus", "listed", "snapshotReady", "availableStock",
}


class SafeFailure(Exception):
    """Only fixed diagnostic labels can escape into the result or terminal."""


class Smoke:
    def __init__(self):
        self.report = {
            "startedAt": datetime.now(timezone.utc).isoformat(), "backend": BASE,
            "checks": [], "sections": {}, "writes": {"stockPosted": False, "sqlUsed": False},
        }

    def check(self, name, condition):
        self.report["checks"].append({"name": name, "passed": bool(condition)})
        if not condition:
            raise SafeFailure(name)

    def request(self, client, name, method, path, *, expected=200, body=None, params=None, headers=None):
        response = client.request(method, path, json=body, params=params, headers=headers)
        try:
            payload = response.json()
        except ValueError:
            payload = {}
        code = payload.get("code", response.status_code) if isinstance(payload, dict) else response.status_code
        passed = code == expected
        self.report["checks"].append({
            "name": name, "httpStatus": response.status_code,
            "code": code, "expected": expected, "passed": passed,
        })
        if not passed:
            raise SafeFailure(name)
        return payload

    def data(self, *args, **kwargs):
        return self.request(*args, **kwargs).get("data")

    def login(self, stack, label, username, password):
        client = stack.enter_context(httpx.Client(base_url=BASE, timeout=30, trust_env=False))
        result = self.request(client, label + ".login", "POST", "/login",
                              body={"username": username, "password": password})
        token = result.get("token")
        self.check(label + ".tokenIssued", isinstance(token, str) and bool(token))
        client.headers.update({"Authorization": "Bearer " + token, "X-Shop-ID": "default"})
        return client

    @staticmethod
    def proof(account, tenant, method, path, owner="", *, shop="default", nonce=None, stamp=None):
        stamp = stamp or str(int(time.time()))
        nonce = nonce or secrets.token_hex(16)
        canonical = "\n".join((tenant, shop, method.upper(), path, owner, stamp, nonce))
        signature = hmac.new(account["assertionSecret"].encode(), canonical.encode(), hashlib.sha256).hexdigest()
        return {
            "X-Shop-ID": shop, "X-Customer-Owner": owner, "X-Customer-Timestamp": stamp,
            "X-Customer-Nonce": nonce, "X-Customer-Signature": signature,
        }

    def customer(self, stack, tenant, account):
        client = self.login(stack, tenant + ".service", account["username"], account["password"])
        owner = hashlib.sha256(("engineering-boundary-owner:" + tenant).encode()).hexdigest()
        other_owner = hashlib.sha256(("engineering-boundary-other:" + tenant).encode()).hexdigest()
        other_tenant = "studio" if tenant == "demo" else "demo"
        prefix = tenant + ".service."
        self.request(client, prefix + "catalogWithoutProofDenied", "GET", "/commerce/catalog", expected=403)
        self.request(client, prefix + "ordersWithoutProofDenied", "GET", "/commerce/orders",
                     params={"ownerId": owner}, expected=403)
        proof = self.proof(account, tenant, "GET", "/commerce/catalog")
        catalog = self.data(client, prefix + "signedPublicCatalog", "GET", "/commerce/catalog", headers=proof)
        self.check(prefix + "publicFieldsOnly", isinstance(catalog, list) and bool(catalog)
                   and all(set(row) == PUBLIC_FIELDS for row in catalog))
        self.request(client, prefix + "nonceReplayDenied", "GET", "/commerce/catalog", headers=proof, expected=403)
        identity = self.data(client, prefix + "signedContext", "GET", "/commerce/context",
                             headers=self.proof(account, tenant, "GET", "/commerce/context"))
        self.check(prefix + "tenantBoundIdentity", identity.get("tenantId") == tenant)
        self.data(client, prefix + "signedOwnOrderRead", "GET", "/commerce/orders", params={"ownerId": owner},
                  headers=self.proof(account, tenant, "GET", "/commerce/orders", owner))
        self.request(client, prefix + "queryOwnerTamperDenied", "GET", "/commerce/orders",
                     params={"ownerId": other_owner},
                     headers=self.proof(account, tenant, "GET", "/commerce/orders", owner), expected=403)
        self.request(client, prefix + "bodyOwnerTamperDenied", "POST", "/commerce/orders",
                     body={"ownerId": other_owner, "requestKey": "engineering_denied", "items": []},
                     headers=self.proof(account, tenant, "POST", "/commerce/orders", owner), expected=403)
        self.request(client, prefix + "otherShopMembershipDenied", "GET", "/commerce/catalog",
                     headers=self.proof(account, tenant, "GET", "/commerce/catalog", shop="otherShop"), expected=403)
        shop_tamper = self.proof(account, tenant, "GET", "/commerce/catalog")
        shop_tamper["X-Shop-ID"] = "otherShop"
        self.request(client, prefix + "shopSignatureTamperDenied", "GET", "/commerce/catalog", headers=shop_tamper, expected=403)
        self.request(client, prefix + "crossTenantSignatureDenied", "GET", "/commerce/catalog",
                     headers=self.proof(account, other_tenant, "GET", "/commerce/catalog"), expected=403)
        tenant_header = self.proof(account, tenant, "GET", "/commerce/catalog")
        tenant_header["X-Tenant-ID"] = other_tenant
        self.request(client, prefix + "crossTenantHeaderDenied", "GET", "/commerce/catalog", headers=tenant_header, expected=403)
        for path in ("/commerce/shops", "/commerce/inventory", "/commerce/planning/replenishment", "/commerce/operations/health"):
            self.request(client, prefix + "merchantDenied:" + path, "GET", path,
                         headers=self.proof(account, tenant, "GET", path), expected=403)
        for path in ("/baseDate/product/list?pageSize=1", "/inventory/inventoryItemInquiry/list?pageSize=1", "/getInfo"):
            self.request(client, prefix + "legacyDenied:" + path, "GET", path, expected=403)
        return {"tenantId": identity["tenantId"], "publicProductCount": len(catalog), "merchantAndLegacyDenied": True}

    def merchant(self, stack, tenant):
        username = os.getenv("FUSION_SMOKE_USER", "admin") if tenant == "demo" else os.getenv("FUSION_STUDIO_USER", "studio_admin")
        client = self.login(stack, tenant + ".admin", username, os.getenv("FUSION_SMOKE_PASSWORD", "admin123"))
        prefix = tenant + ".admin."
        identity = self.data(client, prefix + "context", "GET", "/commerce/context")
        self.check(prefix + "tenantBinding", identity.get("tenantId") == tenant)
        info = self.request(client, prefix + "getInfo", "GET", "/getInfo")
        self.check(prefix + "adminRole", "admin" in info.get("roles", []))
        shops = self.data(client, prefix + "shops", "GET", "/commerce/shops")
        default = next((row for row in shops if row.get("shopId") == "default"), {})
        self.check(prefix + "ownerCapabilities", default.get("memberRole") == "OWNER"
                   and set(default.get("capabilities", [])) == OWNER_CAPABILITIES)
        health = self.data(client, prefix + "operationsHealth", "GET", "/commerce/operations/health")
        self.check(prefix + "databaseAndRedis", health.get("database") is True and health.get("redis") is True)
        schema = health.get("schema", [])
        self.check(prefix + "elevenAppliedMigrations", [row.get("version") for row in schema] == list(range(1,12)) and all(row.get("state") == "APPLIED" for row in schema))
        planning = self.data(client, prefix + "replenishmentWithCompatibleJoins", "GET", "/commerce/planning/replenishment")
        policy = planning.get("dispatchPolicy")
        self.check(prefix + "dispatchPolicyProjection", isinstance(policy,dict) and set(('dailyItemCapacity','dispatchDays','actorId','updatedAt')).issubset(policy))
        self.check(prefix + "workersReady", health.get("ready") is True)
        self.check(prefix + "operationsHealthy", health.get("healthy") is True and health.get("alerts") == [])
        self.report["sections"][tenant + ".admin"] = {
            "status": "PASSED", "tenantId": tenant, "memberRole": default["memberRole"],
            "capabilities": sorted(default["capabilities"]), "database": health["database"], "redis": health["redis"],
            "migrationCount": len(schema), "migrationStates": [row["state"] for row in schema],
            "healthReady": health["ready"], "healthHealthy": health.get("healthy"), "healthAlerts": health.get("alerts", []),
        }
        return client, identity

    def reviewer(self, stack):
        client = self.login(stack, "demo.reviewer", "demo_supply_reviewer", os.getenv("FUSION_SMOKE_PASSWORD", "admin123"))
        identity = self.data(client, "demo.reviewer.context", "GET", "/commerce/context")
        info = self.request(client, "demo.reviewer.getInfo", "GET", "/getInfo")
        self.check("demo.reviewer.role", set(info.get("roles", [])) == {"shop_staff"})
        shops = self.data(client, "demo.reviewer.shops", "GET", "/commerce/shops")
        default = next((row for row in shops if row.get("shopId") == "default"), {})
        self.check("demo.reviewer.capabilities", default.get("memberRole") == "SUPPLY_REVIEWER"
                   and set(default.get("capabilities", [])) == {"READ", "SUPPLY_REVIEW"})
        # An absent product/after-sales ID remains safe if the boundary regresses.
        self.request(client, "demo.reviewer.stockMutationDenied", "POST", "/commerce/planning/conditions", expected=403,
                     body={"requestKey": "engineering_denied_stock", "productId": 9223372036854775806,
                           "warehouseId": 1, "quantity": 1, "from": "SELLABLE", "to": "DAMAGED", "reason": "边界验证"})
        for action in ("review", "sandbox-refund"):
            self.request(client, "demo.reviewer.refundMutationDenied:" + action, "POST",
                         "/commerce/after-sales/engineering_nonexistent/" + action,
                         body={"requestKey": "engineering_denied_refund", "decision": "APPROVE", "note": "边界验证"}, expected=403)
        self.request(client, "demo.reviewer.legacyDenied", "GET", "/baseDate/product/list?pageSize=1", expected=403)
        self.report["sections"]["demo.reviewer"] = {
            "status": "PASSED", "memberRole": default["memberRole"], "capabilities": default["capabilities"],
            "stockAndRefundDenied": True,
        }
        return client, identity

    def procurement(self, admin, admin_identity, reviewer, reviewer_identity):
        facts = self.data(admin, "procurement.realReplenishment", "GET", "/commerce/planning/replenishment")
        candidates = [row for row in facts["items"] if int(row.get("suggestedQuantity", 0)) > 0]
        if not candidates:
            self.report["sections"]["procurement"] = {
                "status": "SKIPPED", "reason": "No current suggestedQuantity above zero; no stock or SQL changes made",
                "productsRead": len(facts["items"]),
            }
            return
        candidates.sort(key=lambda row: (row.get("productCode") != "LAB-TAPO-T300",
                                          int(row.get("availableStock", 0)) != 0, str(row["productId"])))
        selected = candidates[0]
        pid = selected["productId"]
        initial_committed = int(selected["committedSupplyQuantity"])
        initial_physical = (selected["onHandStock"], selected["unavailableStock"])
        run_key = "engineering_" + secrets.token_hex(10)
        draft_id = None
        cancelled = False
        try:
            draft = self.data(admin, "procurement.createDraft", "POST", "/commerce/planning/drafts",
                              body={"requestKey": run_key, "items": [{"productId": pid, "quantity": 1}]})
            draft_id = draft["draftId"]
            self.report["writes"].update({"draftId": draft_id, "productCode": selected["productCode"], "quantity": 1})
            self.check("procurement.pendingNotExecuted", draft["status"] == "PENDING_APPROVAL"
                       and draft["executionStatus"] == "NOT_EXECUTED" and draft["stockPosted"] is False)
            self.check("procurement.creator", int(draft["createdBy"]) == int(admin_identity["userId"]))
            now = self.data(admin, "procurement.afterCreateFacts", "GET", "/commerce/planning/replenishment")
            committed = next(row for row in now["items"] if row["productId"] == pid)
            self.check("procurement.commitmentReserved", int(committed["committedSupplyQuantity"]) == initial_committed + 1)
            review_body = {"requestKey": run_key + "_review", "decision": "APPROVE", "note": "工程边界验证：独立审批，随后撤销，不执行采购"}
            self.request(admin, "procurement.selfReviewDenied", "POST", f"/commerce/planning/drafts/{draft_id}/review",
                         body=review_body, expected=403)
            approved = self.data(reviewer, "procurement.independentApproval", "POST", f"/commerce/planning/drafts/{draft_id}/review", body=review_body)
            self.check("procurement.approvedByDifferentActor", approved["status"] == "APPROVED"
                       and int(approved["reviewedBy"]) == int(reviewer_identity["userId"])
                       and int(approved["reviewedBy"]) != int(approved["createdBy"])
                       and approved["executionStatus"] == "NOT_EXECUTED" and approved["stockPosted"] is False)
            replay = self.data(reviewer, "procurement.sameReviewReplay", "POST", f"/commerce/planning/drafts/{draft_id}/review", body=review_body)
            self.check("procurement.reviewReplayStable", replay == approved)
            self.request(reviewer, "procurement.reviewDecisionChangeDenied", "POST", f"/commerce/planning/drafts/{draft_id}/review",
                         body={"requestKey": run_key + "_changed", "decision": "REJECT", "note": "此修改应被拒绝"}, expected=409)
            cancellation = {"requestKey": run_key + "_cancel", "reason": "工程边界验证结束，取消未执行采购承诺，保留审批记录"}
            result = self.data(admin, "procurement.cancelApprovedUnexecuted", "POST", f"/commerce/planning/drafts/{draft_id}/cancel", body=cancellation)
            cancelled = result["status"] == "CANCELLED"
            self.check("procurement.cancelledLinesRetained", cancelled and result["supplyLines"]
                       and all(line["state"] == "CANCELLED" and line["incomingId"] is None for line in result["supplyLines"]))
            self.data(admin, "procurement.cancelReplay", "POST", f"/commerce/planning/drafts/{draft_id}/cancel", body=cancellation)
            after = self.data(admin, "procurement.afterCancelFacts", "GET", "/commerce/planning/replenishment")
            final = next(row for row in after["items"] if row["productId"] == pid)
            self.check("procurement.commitmentReleased", int(final["committedSupplyQuantity"]) == initial_committed)
            self.check("procurement.physicalStockUnchanged", (final["onHandStock"], final["unavailableStock"]) == initial_physical)
            retained = self.data(admin, "procurement.retainedDraftRead", "GET", "/commerce/planning/drafts")
            retained = next((row for row in retained if row["draftId"] == draft_id), {})
            self.check("procurement.auditRetained", retained.get("status") == "CANCELLED"
                       and retained.get("createdAt") is not None and retained.get("reviewedAt") is not None
                       and retained.get("reviewedBy") == reviewer_identity["userId"])
            self.report["sections"]["procurement"] = {
                "status": "PASSED", "draftId": draft_id, "productCode": selected["productCode"], "quantity": 1,
                "realSuggestedQuantity": selected["suggestedQuantity"], "createdBy": draft["createdBy"],
                "reviewedBy": approved["reviewedBy"], "finalStatus": retained["status"],
                "commitmentBefore": initial_committed, "commitmentAfter": final["committedSupplyQuantity"],
                "physicalStockUnchanged": True, "auditRetained": True,
            }
        finally:
            if draft_id and not cancelled:
                cleanup = self.data(admin, "procurement.failureCleanupCancel", "POST", f"/commerce/planning/drafts/{draft_id}/cancel",
                                    body={"requestKey": run_key + "_cleanup", "reason": "边界验证中断，取消未执行草稿并保留业务审计"})
                self.report["writes"]["cleanupStatus"] = cleanup.get("status")

    def section(self, name, action):
        try:
            result = action()
            self.report["sections"].setdefault(name, {"status": "PASSED"})
            return result
        except Exception as error:
            self.report["sections"][name] = {
                "status": "FAILED", "errorType": type(error).__name__,
                "failedCheck": str(error) if isinstance(error, SafeFailure) else "Diagnostic suppressed to protect credentials",
            }
            return None

    def run(self):
        if urlparse(BASE).hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise SafeFailure("This smoke is limited to the local learning deployment")
        credentials = json.loads(CREDENTIALS_PATH.read_text(encoding="utf-8"))
        with ExitStack() as stack:
            for tenant in ("demo", "studio"):
                result = self.section(tenant + ".service", lambda t=tenant: self.customer(stack, t, credentials[t]))
                if result:
                    self.report["sections"][tenant + ".service"].update(result)
            admin = self.section("demo.admin", lambda: self.merchant(stack, "demo"))
            self.section("studio.admin", lambda: self.merchant(stack, "studio"))
            reviewer = self.section("demo.reviewer", lambda: self.reviewer(stack))
            if admin and reviewer:
                self.section("procurement", lambda: self.procurement(admin[0], admin[1], reviewer[0], reviewer[1]))
            else:
                self.report["sections"]["procurement"] = {"status": "SKIPPED", "reason": "Required merchant identities unavailable"}
        statuses = [section["status"] for section in self.report["sections"].values()]
        self.report["status"] = "FAILED" if "FAILED" in statuses else "PARTIAL" if "SKIPPED" in statuses else "PASSED"
        self.report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        self.report["passedChecks"] = sum(check["passed"] for check in self.report["checks"])
        self.report["failedChecks"] = sum(not check["passed"] for check in self.report["checks"])
        RESULT_PATH.parent.mkdir(parents=True, exist_ok=True)
        RESULT_PATH.write_text(json.dumps(self.report, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        print(json.dumps({"status": self.report["status"], "passedChecks": self.report["passedChecks"],
                          "failedChecks": self.report["failedChecks"], "sections": self.report["sections"],
                          "resultPath": str(RESULT_PATH)}, ensure_ascii=False, separators=(",", ":")))
        return 0 if self.report["status"] == "PASSED" else 1


if __name__ == "__main__":
    try:
        sys.exit(Smoke().run())
    except Exception as error:
        print(json.dumps({"status": "FAILED", "errorType": type(error).__name__,
                          "diagnostic": str(error) if isinstance(error, SafeFailure) else "Diagnostic suppressed to protect credentials"}))
        sys.exit(1)
