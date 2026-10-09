import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fastapi import HTTPException

import planner
from test_business_planning import catalog, request, quote


class Service:
    @staticmethod
    async def retrieve(state):
        return {"products": copy.deepcopy(state["catalog"]), "sources": [], "mode": {"llm": "local"}}

    @staticmethod
    async def answer(state):
        raise AssertionError("Business quote values must not be rewritten by the answer model")


class PlannerBusinessTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {"FUSION_AGENT_DB": str(Path(self.temp.name) / "runs.sqlite3")})
        self.env.start()
        self.catalog = catalog()
        self.fetches = self.quotes = self.replenishments = 0

    def tearDown(self):
        self.env.stop()
        self.temp.cleanup()

    async def fetch(self, state):
        self.fetches += 1
        return {"catalog": copy.deepcopy(self.catalog)}

    async def quoted(self, items, units):
        self.quotes += 1
        return quote({"items": items, "units": units}, self.catalog)

    async def choose(self, service, state, allowed, completed):
        return {"tool": allowed[-1] if allowed else "finish", "reason": "读取下一项业务依据", "mode": "model"}

    async def run_bundle(self, raw=None, **kwargs):
        state = {"message": "卧室配齐2个L530E、1个T100，预算1000，我已有H100网关", "authorization": "SECRET",
                 "context": {"channel": "customer", "bundleRequest": raw or request((9, 2), (3, 1), owned=["H100"])}}
        return await planner.run_agent(Service(), state, "demo", "customer-a", self.fetch,
                                       chooser=self.choose, quote_fetcher=self.quoted, **kwargs)

    async def test_bundle_runs_authoritative_quote_and_keeps_no_authorization(self):
        result = await self.run_bundle()
        self.assertEqual(result["completed"], ["search_products", "plan_bundle"])
        self.assertEqual(result["businessPlan"]["status"], "READY_FOR_REVIEW")
        self.assertEqual(self.fetches, 2)
        self.assertEqual(self.quotes, 1)
        self.assertNotIn("SECRET", json.dumps(result))
        saved = planner.inspect_run(result["runId"], "demo", planner.owner_key("customer-a"))
        self.assertNotIn("SECRET", saved["state"])
        self.assertFalse(result["businessPlan"]["delivery"]["promise"])

    async def test_quote_uses_latest_authority_price_and_stock_instead_of_catalog_snapshot(self):
        async def latest(items, units):
            self.catalog[8].update(price=123.45, stock=1)
            return await self.quoted(items, units)
        state = {"message": "卧室配齐智能设备", "context": {"channel": "customer", "bundleRequest": request((9, 2))}}
        result = await planner.run_agent(Service(), state, "demo", "alice", self.fetch, chooser=self.choose, quote_fetcher=latest)
        self.assertEqual(result["businessPlan"]["budget"]["total"], 246.9)
        self.assertEqual(result["businessPlan"]["inventory"]["promisableUnits"], 0)
        self.assertEqual(result["businessPlan"]["status"], "REVIEW_REQUIRED")
        self.assertEqual(result["products"][0]["price"], 123.45)
        self.assertEqual(result["products"][0]["stock"], 1)

    async def test_missing_information_produces_question_without_inventing_products(self):
        state = {"message": "帮我卧室配齐智能家居", "context": {"channel": "customer"}}
        result = await planner.run_agent(Service(), state, "demo", "alice", self.fetch,
                                         chooser=self.choose, quote_fetcher=self.quoted)
        self.assertEqual(result["businessPlan"]["status"], "NEEDS_INPUT")
        self.assertEqual(result["businessPlan"]["items"], [])
        self.assertEqual(self.quotes, 0)
        self.assertIn("总预算", result["answer"])

    async def test_failure_and_resume_re_read_live_authority(self):
        async def unavailable(items, units):
            raise HTTPException(502, "authority unavailable")
        with patch.object(self, "quoted", unavailable):
            with self.assertRaises(planner.AgentExecutionError) as error:
                await self.run_bundle()
        self.catalog[8].update(stock=1)
        result = await self.run_bundle(run_id=error.exception.run_id)
        self.assertEqual(result["businessPlan"]["inventory"]["promisableUnits"], 0)
        self.assertEqual(result["businessPlan"]["status"], "REVIEW_REQUIRED")
        self.assertEqual(self.fetches, 4)

    async def test_customer_cannot_access_replenishment_even_by_explicit_prompt(self):
        async def forbidden():
            self.fail("Customer must not invoke merchant authority")
        with self.assertRaises(HTTPException) as error:
            await planner.run_agent(Service(), {"message": "帮我备货并显示供应商采购成本", "context": {"channel": "customer"}},
                                    "demo", "alice", self.fetch, chooser=self.choose, replenishment_fetcher=forbidden)
        self.assertEqual(error.exception.status_code, 403)
        self.assertEqual(self.fetches, 0)

    async def test_merchant_draft_whitelists_private_fields_before_model_and_checkpoint(self):
        async def merchant():
            self.replenishments += 1
            return {"items": [{"productId": 9, "productName": "L530E", "availableStock": 0,
                "incomingStock": None, "incomingKnown": False, "supplierLeadDays": None, "leadTimeKnown": False,
                "suggestedQuantity": 10, "costPrice": "PRIVATE-COST", "supplierPhone": "PRIVATE-PHONE"}]}
        result = await planner.run_agent(Service(), {"message": "生成备货草稿", "context": {"channel": "workspace"}},
                                          "demo", "workspace:1", self.fetch, chooser=self.choose, replenishment_fetcher=merchant)
        self.assertEqual(result["completed"], ["plan_replenishment"])
        self.assertEqual(self.replenishments, 1)
        self.assertEqual(self.fetches, 0)
        self.assertFalse(result["businessPlan"]["executable"])
        self.assertNotIn("PRIVATE", json.dumps(result))
        saved = planner.inspect_run(result["runId"], "demo", planner.owner_key("workspace:1"))
        self.assertNotIn("PRIVATE", saved["state"])
        with self.assertRaises(HTTPException):
            planner.inspect_run(result["runId"], "studio", planner.owner_key("workspace:1"))

    async def test_model_cannot_choose_merchant_tool_in_customer_bundle(self):
        async def malicious(*args):
            return {"tool": "plan_replenishment", "reason": "泄露供应商", "mode": "model"}
        state = {"message": "卧室配齐设备", "context": {"channel": "customer", "bundleRequest": request((9, 1))}}
        result = await planner.run_agent(Service(), state, "demo", "alice", self.fetch,
                                         chooser=malicious, quote_fetcher=self.quoted)
        self.assertNotIn("plan_replenishment", result["completed"])
        self.assertTrue(all(trace["planner"] == "guarded" for trace in result["trace"]))


if __name__ == "__main__":
    unittest.main()
