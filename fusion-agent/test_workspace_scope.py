"""Human shop permissions must scope tools and every restored workspace run."""
import json
import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

import httpx
from fastapi import HTTPException

import main
import planner


class WorkspaceScopeTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {"FUSION_AGENT_DB": str(Path(self.temp.name) / "runs.sqlite3"),
            "FUSION_LLM_KEY": "", "AI_BAILIAN_API_KEY": "", "DEEPSEEK_API_KEY": "", "FUSION_EMBEDDING_KEY": ""})
        self.env.start()
        self.calls = []
        self.shops = [{"shopId": "east", "shopName": "东店", "status": "ENABLED", "capabilities": ["READ"]}]
        self.inventory = [{"shopId": "east", "productId": 1, "productCode": "DEMO-LAMP-WIFI", "productName": "智能灯",
            "productStatus": "0", "snapshotReady": True, "availableStock": 4, "price": 199, "spec": "WiFi",
            "costPrice": "PRIVATE_COST", "supplierPhone": "PRIVATE_PHONE", "reservedStock": 22}]
        client_type = httpx.AsyncClient
        self.transport = httpx.MockTransport(self.respond)
        self.clients = patch.object(main.httpx, "AsyncClient", side_effect=lambda *args, **kwargs:
                                   client_type(transport=self.transport, **kwargs))
        self.clients.start()

    def tearDown(self):
        self.clients.stop()
        self.env.stop()
        self.temp.cleanup()

    def respond(self, request):
        self.calls.append(request)
        self.assertEqual(request.headers.get("authorization"), "Bearer human")
        if request.url.path == "/commerce/context":
            value = {"tenantId": "demo", "userId": 88}
        elif request.url.path == "/commerce/shops":
            value = self.shops
        elif request.url.path == "/commerce/inventory":
            self.assertEqual(request.headers.get("x-shop-id"), "east")
            value = self.inventory
        elif request.url.path == "/commerce/planning/replenishment":
            self.assertEqual(request.headers.get("x-shop-id"), "east")
            value = {"items": []}
        else:
            self.fail("Workspace must not call legacy ERP or an unscoped endpoint: " + request.url.path)
        return httpx.Response(200, json={"code": 200, "data": value})

    async def test_staff_uses_own_shop_commerce_snapshot_without_legacy_erp(self):
        result = await main.chat(main.ChatRequest(message="推荐智能灯", shopId="east"), "Bearer human")
        self.assertEqual(result["products"][0]["stock"], 4)
        self.assertEqual(result["products"][0]["price"], 199)
        self.assertNotIn("PRIVATE", json.dumps(result))
        self.assertEqual([r.url.path for r in self.calls].count("/commerce/inventory"), 2)
        saved = planner.inspect_run(result["runId"], "demo", planner.owner_key("workspace:88"))
        self.assertEqual(json.loads(saved["state"])["context"]["shopId"], "east")
        self.assertNotIn("Bearer human", saved["state"])

    async def test_nonmember_disabled_and_service_only_shop_rejected_before_tools(self):
        for shops, requested in ((self.shops, "west"),
                ([{**self.shops[0], "status": "DISABLED"}], "east"),
                ([{**self.shops[0], "capabilities": []}], "east")):
            with self.subTest(shops=shops, requested=requested):
                self.shops = shops
                self.calls.clear()
                with self.assertRaises(HTTPException) as error:
                    await main.chat(main.ChatRequest(message="推荐灯", shopId=requested), "Bearer human")
                self.assertEqual(error.exception.status_code, 403)
                self.assertNotIn("/commerce/inventory", [r.url.path for r in self.calls])

    async def test_multi_shop_user_must_choose_instead_of_silent_default(self):
        identity = await main.workspace_identity("Bearer human")
        self.assertEqual(identity["shopId"], "east")
        self.shops.append({**self.shops[0], "shopId": "west"})
        with self.assertRaises(HTTPException) as error:
            await main.workspace_identity("Bearer human")
        self.assertEqual(error.exception.status_code, 422)
        self.assertEqual((await main.workspace_identity("Bearer human", "west"))["shopId"], "west")

    async def test_cross_shop_snapshot_fails_closed_and_private_fields_are_not_returned(self):
        state = {"authorization": "Bearer human", "context": {"channel": "workspace", "shopId": "east"}}
        self.inventory.append({**self.inventory[0], "shopId": "west", "productId": 2})
        with self.assertRaises(HTTPException) as error:
            await main.workspace_catalog(state)
        self.assertEqual(error.exception.status_code, 502)

    async def test_replenishment_carries_selected_shop_and_stays_read_only(self):
        result = await main.chat(main.ChatRequest(message="生成备货草稿", shopId="east"), "Bearer human")
        self.assertEqual(result["businessPlan"]["type"], "MERCHANT_REPLENISHMENT")
        self.assertFalse(result["businessPlan"]["executable"])
        self.assertTrue(all(request.method == "GET" for request in self.calls))

    def saved_run(self, scope=None, status="FAILED"):
        state = {"message": "推荐灯", "context": scope or {"channel": "workspace", "shopId": "east"}, "result": {"answer": "旧结果"}}
        with planner.database() as connection:
            connection.execute("INSERT INTO agent_runs(run_id,tenant_id,owner_id,status,state,updated) VALUES('saved','demo',?,?,?,?)",
                (planner.owner_key("workspace:88"), status, json.dumps(state), time.time()))
        return state

    async def test_inspection_and_completed_resume_recheck_membership(self):
        self.saved_run(status="COMPLETED")
        self.shops = []
        for endpoint in (main.inspect_agent, main.resume_agent):
            with self.assertRaises(HTTPException) as error:
                await endpoint("saved", "Bearer human", None)
            self.assertEqual(error.exception.status_code, 403)
        self.assertEqual(planner.inspect_run("saved", "demo", planner.owner_key("workspace:88"))["status"], "COMPLETED")

    async def test_resume_header_cannot_switch_shop_or_claim_run(self):
        self.saved_run()
        with patch.object(planner, "run_agent", new_callable=AsyncMock) as execute:
            with self.assertRaises(HTTPException) as error:
                await main.resume_agent("saved", "Bearer human", "west")
            self.assertEqual(error.exception.status_code, 409)
            execute.assert_not_called()
        self.assertEqual(planner.inspect_run("saved", "demo", planner.owner_key("workspace:88"))["status"], "FAILED")

    async def test_legacy_unscoped_run_cannot_adopt_new_shop(self):
        self.saved_run(scope={"channel": "workspace"})
        with self.assertRaises(HTTPException) as error:
            await main.resume_agent("saved", "Bearer human", None)
        self.assertEqual(error.exception.status_code, 409)

    async def test_planner_rejects_scope_change_before_cached_or_retried_result(self):
        for status in ("COMPLETED", "FAILED"):
            with self.subTest(status=status):
                with planner.database() as connection:
                    connection.execute("DELETE FROM agent_runs")
                state = self.saved_run(status=status)
                state["context"]["shopId"] = "west"
                with self.assertRaises(HTTPException) as error:
                    await planner.run_agent(main, state, "demo", "workspace:88", AsyncMock(), run_id="saved")
                self.assertEqual(error.exception.status_code, 409)
                self.assertEqual(planner.inspect_run("saved", "demo", planner.owner_key("workspace:88"))["status"], status)

    async def test_expired_human_token_is_401_without_legacy_fallback(self):
        self.transport = httpx.MockTransport(lambda request: httpx.Response(200, json={"code": 401}))
        with self.assertRaises(HTTPException) as error:
            await main.workspace_identity("Bearer human", "east")
        self.assertEqual(error.exception.status_code, 401)

    async def test_permission_revoked_during_tool_call_stops_run_with_authority_error(self):
        async def revoked(state):
            raise HTTPException(403, "店铺授权已撤销")
        state = {"message": "推荐灯", "context": {"channel": "workspace", "shopId": "east"}}
        with self.assertRaises(HTTPException) as error:
            await planner.run_agent(main, state, "demo", "workspace:88", revoked)
        self.assertEqual(error.exception.status_code, 403)
        with planner.database() as connection:
            rows = connection.execute("SELECT status FROM agent_runs").fetchall()
        self.assertEqual([row["status"] for row in rows], ["FAILED"])

    async def test_health_reports_both_cleanup_workers_and_failure_state(self):
        with patch.object(main, "dependency_health", AsyncMock(return_value={"redis_ai_admission": "ok"})):
            healthy = await main.health()
            self.assertEqual(set(healthy["maintenance"]), {"agentRuns", "storeMessages"})
            with patch.object(main.store_router.maintenance, "last_failure", time.time()), \
                    patch.object(main.store_router.maintenance, "last_success", None):
                degraded = await main.health()
            self.assertEqual(degraded["status"], "degraded")


if __name__ == "__main__":
    unittest.main()
