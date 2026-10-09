import json
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient

from business_tasks import proposals, propose_targets, build_business_task_router


def business_plan():
    return {"type": "MERCHANT_REPLENISHMENT", "items": [
        {"productId": 1, "productName": "Lamp", "suggestedQuantity": 8, "leadTimeKnown": True, "supplierLeadDays": 3},
        {"productId": 2, "productName": "Hub", "suggestedQuantity": 0},
        {"productId": 3, "productName": "Sensor", "suggestedQuantity": 1500}]}


def service(key=""):
    return SimpleNamespace(llm_key=lambda: key, llm_defaults=lambda: ("https://provider.invalid", "test-model"),
                           redact_query=lambda text: text,
                           workspace_identity=AsyncMock(return_value={"tenantId": "demo", "userId": 1, "shopId": "default"}),
                           workspace_catalog=AsyncMock())


class ProposalTests(unittest.IsolatedAsyncioTestCase):
    async def test_rules_mode_uses_real_proposal_and_caps_not_fabricated_quantity(self):
        items, mode = await propose_targets(service(), "补货建议", business_plan())
        self.assertEqual(mode, "rules")
        self.assertEqual(items, [{"productId": 1, "quantity": 8}, {"productId": 3, "quantity": 999}])

    async def test_actual_model_selection_is_preserved_as_task_proposal(self):
        response = httpx.Response(200, request=httpx.Request("POST", "https://provider.invalid"),
                                  json={"choices": [{"message": {"content": json.dumps({"items": [{"productId": 1, "quantity": 2}]})}}]})
        with patch("business_tasks.provider_post", new=AsyncMock(return_value=response)) as call:
            items, mode = await propose_targets(service("private-provider-key"), "只采购少量灯具", business_plan())
        self.assertEqual((items, mode), ([{"productId": 1, "quantity": 2}], "model"))
        self.assertEqual(call.call_args.kwargs["operation"], "procurement_proposal")
        self.assertNotIn("private-provider-key", json.dumps(items))

    async def test_model_cannot_add_products_exceed_quantity_or_hide_action(self):
        for items in [[{"productId": 8, "quantity": 1}], [{"productId": 1, "quantity": 9}],
                      [{"productId": 1, "quantity": True}], [{"productId": 1, "quantity": 1, "approve": True}],
                      [{"productId": 1, "quantity": 1}, {"productId": 1, "quantity": 1}], []]:
            response = httpx.Response(200, request=httpx.Request("POST", "https://provider.invalid"),
                                      json={"choices": [{"message": {"content": json.dumps({"items": items})}}]})
            with patch("business_tasks.provider_post", new=AsyncMock(return_value=response)):
                with self.assertRaises(HTTPException) as caught:
                    await propose_targets(service("key"), "补货", business_plan())
                self.assertEqual(caught.exception.status_code, 502)

    async def test_no_need_and_invalid_facts_never_create_placeholder_plan(self):
        with self.assertRaises(HTTPException) as error:
            await propose_targets(service(), "补货", {"type": "MERCHANT_REPLENISHMENT", "items": []})
        self.assertEqual(error.exception.status_code, 409)
        for malformed in [None, {"type": "CUSTOMER", "items": []},
                          {"type": "MERCHANT_REPLENISHMENT", "items": [{"productId": True, "suggestedQuantity": 1}]}]:
            with self.assertRaises(HTTPException):
                proposals(malformed)


class TaskFacadeTests(unittest.TestCase):
    def client(self, svc):
        app = FastAPI();app.include_router(build_business_task_router(svc));return TestClient(app)

    def test_revoked_authority_is_checked_before_model(self):
        svc = service();svc.authority_data = AsyncMock(side_effect=HTTPException(403, "revoked"))
        with patch("business_tasks.run_agent", new=AsyncMock()) as planner:
            result = self.client(svc).post("/business/tasks/procurement", headers={"Authorization": "Bearer human"},
                                          json={"shopId": "default", "requestKey": "new", "goal": "补货计划"})
        self.assertEqual(result.status_code, 403);planner.assert_not_awaited()

    def test_response_loss_retry_reads_java_task_without_another_model_run(self):
        svc = service();svc.authority_data = AsyncMock(return_value={"taskId": "BT123", "goal": "补货计划", "status": "WAITING_APPROVAL"})
        with patch("business_tasks.run_agent", new=AsyncMock()) as planner:
            result = self.client(svc).post("/business/tasks/procurement", headers={"Authorization": "Bearer human"},
                                          json={"shopId": "default", "requestKey": "same", "goal": "补货计划"})
        self.assertEqual(result.status_code, 200);self.assertEqual(result.json()["taskId"], "BT123");planner.assert_not_awaited()

    def test_only_bounded_proposal_evidence_goes_to_java_and_no_bearer_persisted(self):
        svc = service()
        async def authority(auth, method, path, **kwargs):
            if method == "GET":raise HTTPException(404, "absent")
            self.assertEqual(kwargs["shop"], "default")
            self.assertEqual(kwargs["body"]["items"], [{"productId": 1, "quantity": 8}, {"productId": 3, "quantity": 999}])
            self.assertEqual(kwargs["body"]["plannerMode"], "rules")
            self.assertNotIn("Bearer human", json.dumps(kwargs["body"]))
            return {"taskId": "BT456", **kwargs["body"]}
        svc.authority_data = AsyncMock(side_effect=authority)
        with patch("business_tasks.run_agent", new=AsyncMock(return_value={"runId": "read-run", "businessPlan": business_plan()})):
            result = self.client(svc).post("/business/tasks/procurement", headers={"Authorization": "Bearer human"},
                                          json={"shopId": "default", "requestKey": "create", "goal": "补货计划"})
        self.assertEqual(result.status_code, 200);self.assertEqual(result.json()["plannerRunId"], "read-run")

    def test_non_procurement_and_scope_injection_rejected(self):
        svc = service();svc.authority_data = AsyncMock()
        for extra in [{"goal": "帮我直接付款"}, {"goal": "补货计划", "tenantId": "studio"},
                      {"goal": "补货计划", "approve": True}]:
            result = self.client(svc).post("/business/tasks/procurement", json={"shopId": "default", "requestKey": "bounded", **extra})
            self.assertEqual(result.status_code, 422)
        svc.authority_data.assert_not_awaited()


if __name__ == "__main__":unittest.main()
