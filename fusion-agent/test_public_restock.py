import asyncio
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from fastapi import HTTPException
import main
import planner
from business_planning import replenishment_intent, public_restock_intent
from test_planner import Service, PRODUCT


class PublicRestockTests(unittest.IsolatedAsyncioTestCase):
    def test_consumer_arrival_question_is_separate_from_procurement_draft(self):
        for text in ("这个商品什么时候补货？", "T300预计到货时间？", "补货后会通知吗，多久？"):
            self.assertTrue(public_restock_intent(text))
            self.assertFalse(replenishment_intent(text))
        for text in ("帮我生成补货草稿", "采购建议", "读取销量分析缺货风险", "什么时候补货，请生成采购计划"):
            self.assertTrue(replenishment_intent(text))
            self.assertFalse(public_restock_intent(text))

    async def test_public_query_never_reads_supplier_data_or_invents_eta(self):
        state = {"message": "T300什么时候补货？", "context": {"channel": "customer"}, "products": [], "sources": []}
        with patch.object(main, "llm_key", side_effect=AssertionError("No fabricated ETA")):
            result = await main.answer(state)
        self.assertIn("没有", result["answer"])
        self.assertIn("商家核实", result["answer"])
        self.assertEqual(result["mode"]["llm"], "local")

    async def test_public_question_can_complete_but_customer_procurement_is_denied(self):
        async def fetch(state): return {"catalog": [{**PRODUCT}]}
        async def choose(service, state, allowed, completed):
            return {"tool": allowed[0] if allowed else "finish", "reason": "Read public facts", "mode": "rules"}
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {"FUSION_AGENT_DB": str(Path(folder) / "runs.sqlite3")}):
            result = await planner.run_agent(Service(), {"message": "这个台灯什么时候补货", "context": {"channel": "customer"}},
                "demo", "alice", fetch, chooser=choose)
            self.assertNotIn("plan_replenishment", result["completed"])
            with self.assertRaises(HTTPException) as error:
                await planner.run_agent(Service(), {"message": "生成补货草稿", "context": {"channel": "customer"}},
                    "demo", "alice", fetch, chooser=choose)
            self.assertEqual(error.exception.status_code, 403)

    def test_aftersales_explanation_separates_refund_and_sellable_stock(self):
        answer = main.deterministic_answer({"message": "如何申请售后退款", "context": {}, "products": []})
        self.assertIn("选择商品和数量", answer)
        self.assertIn("待质检或损坏", answer)
        self.assertNotIn("整单", answer)


if __name__ == "__main__": unittest.main()
