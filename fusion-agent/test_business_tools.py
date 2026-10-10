import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from fastapi import HTTPException
import planner
from business_tools import TOOL_CATALOG, require_tool, order_review_id, safe_settlement, safe_stock

ORDER = "SC20261011ABC"

class Service:
    def __init__(self): self.calls = []; self.fail = False
    @staticmethod
    def llm_key(): return ""
    async def merchant_order_evidence(self, state, order, tool):
        self.calls.append((order, tool))
        if tool == "read_order_stock":
            return {"orderId": order, "products": [{"productId": 1, "productName": "灯", "quantity": 3}],
                    "warehouseEvents": [{"operation": "DISPATCH", "deltaQuantity": -3}, {"operation": "RETURN", "deltaQuantity": 1}],
                    "holds": [], "shippingAddress": "PRIVATE", "phone": "PRIVATE"}
        if self.fail: raise HTTPException(502, "authority unavailable")
        return {"orderId": order, "currency": "CNY", "provider": "LOCAL_SANDBOX", "externalChannel": False,
                "grossPaid": 316.98, "refundedAmount": 95.97, "netReceipts": 221.01, "netStockCost": 38,
                "costComplete": True, "moneyComplete": True, "approvedExpenseAmount": 15, "settledExpenseAmount": 0,
                "outstandingExpenseAmount": 15, "operatingResult": 168.01, "resultStatus": "CLOSED",
                "expenses": [{"expenseId": "EX1", "status": "APPROVED", "amount": 15, "outstandingAmount": 15,
                              "payee": "PRIVATE", "evidenceReference": "PRIVATE"}], "shippingAddress": "PRIVATE"}

class BusinessToolTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {"FUSION_AGENT_DB": str(Path(self.temp.name) / "runs.sqlite3"), "FUSION_DISABLED_TOOLS": ""})
        self.env.start(); self.service = Service()
    def tearDown(self): self.env.stop(); self.temp.cleanup()
    async def fetch(self, state): raise AssertionError("Order review must not read an unrelated product catalog")
    def state(self, channel="workspace", shop="default"):
        return {"message": f"分析订单 {ORDER} 的费用结算和库存流转", "authorization": "SECRET",
                "context": {"channel": channel, "shopId": shop}}
    async def run_review(self, state=None, **kwargs):
        return await planner.run_agent(self.service, state or self.state(), "demo", "operator", self.fetch, **kwargs)
    async def test_multistep_review_uses_authority_and_proposes_no_write(self):
        result = await self.run_review(); plan = result["businessPlan"]
        self.assertEqual(result["completed"], ["read_order_stock", "read_order_settlement"])
        self.assertEqual(plan["type"], "ORDER_REVIEW"); self.assertFalse(plan["autonomousExecution"])
        self.assertEqual(plan["facts"]["settlement"]["operatingResult"], 168.01)
        self.assertTrue(all(action["requiresApproval"] and not action["executionAllowed"] for action in plan["actions"]))
        saved = planner.inspect_run(result["runId"], "demo", planner.owner_key("operator"))
        self.assertNotIn("SECRET", saved["state"]); self.assertNotIn("PRIVATE", saved["state"])
        self.assertEqual(len(self.service.calls), 2)
    async def test_customer_cannot_ask_for_merchant_finance(self):
        with self.assertRaises(HTTPException) as error: await self.run_review(self.state(channel="customer"))
        self.assertEqual(error.exception.status_code, 403); self.assertEqual(self.service.calls, [])
    async def test_authority_permission_failure_is_not_model_fallback(self):
        async def deny(*args): raise HTTPException(403, "forbidden")
        self.service.merchant_order_evidence = deny
        with self.assertRaises(HTTPException) as error: await self.run_review()
        self.assertEqual(error.exception.status_code, 403)
    async def test_failure_and_resume_re_read_both_business_sources(self):
        self.service.fail = True
        with self.assertRaises(planner.AgentExecutionError) as error: await self.run_review()
        run = error.exception.run_id; self.service.fail = False
        result = await self.run_review(run_id=run)
        self.assertEqual(result["planStatus"], "COMPLETED")
        self.assertEqual([tool for _, tool in self.service.calls].count("read_order_stock"), 2)
    async def test_disabled_tool_fails_closed_and_records_failure(self):
        with patch.dict(os.environ, {"FUSION_DISABLED_TOOLS": "read_order_settlement"}):
            with self.assertRaises(planner.AgentExecutionError) as error: await self.run_review()
        saved = planner.inspect_run(error.exception.run_id, "demo", planner.owner_key("operator"))
        self.assertEqual(saved["status"], "FAILED"); self.assertEqual(self.service.calls, [])
    async def test_resume_cannot_change_shop(self):
        self.service.fail = True
        with self.assertRaises(planner.AgentExecutionError) as error: await self.run_review()
        self.service.fail = False
        with self.assertRaises(HTTPException) as scope: await self.run_review(self.state(shop="other"), run_id=error.exception.run_id)
        self.assertEqual(scope.exception.status_code, 409)
    async def test_missing_evidence_does_not_claim_profit(self):
        original = self.service.merchant_order_evidence
        async def missing(state, order, tool):
            data = await original(state, order, tool)
            if tool == "read_order_settlement": data.update(costComplete=False, operatingResult=None, provisionalContribution=None)
            return data
        self.service.merchant_order_evidence = missing
        result = await self.run_review(); self.assertIn("不能确认经营结果", result["answer"])
        self.assertIn("VERIFY_MISSING_COST_EVIDENCE", [action["kind"] for action in result["businessPlan"]["actions"]])
    def test_tools_are_code_owned_read_contracts(self):
        self.assertTrue(all(tool["effect"] == "READ" for tool in TOOL_CATALOG.values()))
        with self.assertRaises(HTTPException): require_tool("execute_sql", "workspace")
        self.assertEqual(order_review_id(f"{ORDER} 费用"), ORDER)
        with self.assertRaises(HTTPException): order_review_id(f"{ORDER} SCOTHER 费用")
    def test_foreign_or_malformed_evidence_is_rejected(self):
        with self.assertRaises(HTTPException): safe_stock({"orderId": "other"}, ORDER)
        with self.assertRaises(HTTPException): safe_settlement({"orderId": ORDER, "expenses": "bad"}, ORDER)

if __name__ == "__main__": unittest.main()
