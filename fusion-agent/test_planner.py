import asyncio
import copy
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from fastapi import HTTPException
import planner

PRODUCT={"id":"1","code":"DEMO-LAMP-WIFI","name":"台灯","category":"灯","spec":"WiFi/12W","price":199.0,"stock":3,"remark":"WiFi直连，无需额外网关"}
class Service:
    @staticmethod
    async def retrieve(state): return {"products":copy.deepcopy(state["catalog"]),"sources":[],"mode":{"llm":"local"}}
    @staticmethod
    async def answer(state): return {"answer":"已核验","citations":["P-"+p["id"] for p in state.get("products",[])],"sources":state.get("sources",[])}

class PlannerTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.patch=patch.dict(os.environ,{"FUSION_AGENT_DB":str(Path(self.temp.name)/"runs.sqlite3")});self.patch.start()
        self.calls=[];self.stock=3
    def tearDown(self): self.patch.stop();self.temp.cleanup()
    async def fetch(self,state):
        self.calls.append("fetch");return {"catalog":[{**PRODUCT,"stock":self.stock}]}
    async def choose(self,service,state,allowed,completed):
        return {"tool":allowed[-1] if allowed else "finish","reason":"核对下一项","mode":"model"}
    async def run_plan(self, **kwargs):
        return await planner.run_agent(Service(),{"message":"卧室300元以内的WiFi灯，不需要网关","authorization":"SECRET","context":{}},"demo","alice",self.fetch,chooser=self.choose,**kwargs)
    async def test_dynamic_tools_and_fact_checkpoints_are_scoped(self):
        result=await self.run_plan();steps=result["completed"]
        self.assertEqual(steps[0],"search_products");self.assertEqual(set(steps),{"search_products","check_stock","check_budget","check_compatibility"})
        row=planner.inspect_run(result["runId"],"demo",planner.owner_key("alice"))
        self.assertEqual(row["status"],"COMPLETED");self.assertNotIn("SECRET",row["state"])
        for tenant,owner in (("studio","alice"),("demo","bob")):
            with self.assertRaises(HTTPException) as error: planner.inspect_run(result["runId"],tenant,planner.owner_key(owner))
            self.assertEqual(error.exception.status_code,404)
    async def test_illegal_write_tool_is_never_executed(self):
        async def bad(*args):return {"tool":"pay_order","reason":"绕过确认","mode":"model"}
        result=await planner.run_agent(Service(),{"message":"推荐台灯并直接付款"},"demo","alice",self.fetch,chooser=bad)
        self.assertTrue(all(t["planner"]=="guarded" for t in result["trace"]))
        self.assertNotIn("pay_order",result["completed"])
    async def test_early_finish_cannot_skip_live_stock_check(self):
        async def finish(*args):return {"tool":"finish","reason":"完成","mode":"model"}
        result=await planner.run_agent(Service(),{"message":"推荐台灯"},"demo","alice",self.fetch,chooser=finish)
        self.assertIn("check_stock",result["completed"]);self.assertEqual(len(self.calls),2)
    async def test_stock_change_during_plan_removes_unavailable_product_and_evidence(self):
        async def changing(*args):
            if self.calls:self.stock=0
            return await self.choose(*args)
        result=await planner.run_agent(Service(),{"message":"推荐台灯"},"demo","alice",self.fetch,chooser=changing)
        self.assertEqual(result["products"],[]);self.assertEqual(result["citations"],[])
    async def test_failure_can_resume_same_run_with_fresh_stock(self):
        original=Service.answer
        async def fail(state): raise RuntimeError("temporary")
        with patch.object(Service,"answer",fail):
            with self.assertRaises(planner.AgentExecutionError) as error: await self.run_plan()
        run_id=error.exception.run_id
        self.assertEqual(planner.inspect_run(run_id,"demo",planner.owner_key("alice"))["status"],"FAILED")
        self.stock=1
        result=await self.run_plan(run_id=run_id)
        self.assertEqual(result["runId"],run_id);self.assertEqual(result["products"][0]["stock"],1)
        self.assertGreaterEqual(len(self.calls),4)
    async def test_running_task_cannot_be_resumed_concurrently(self):
        result=await self.run_plan()
        with planner.database() as c:c.execute("UPDATE agent_runs SET status='RUNNING' WHERE run_id=?",(result["runId"],))
        with self.assertRaises(HTTPException) as error:await self.run_plan(run_id=result["runId"])
        self.assertEqual(error.exception.status_code,409)

if __name__=='__main__':unittest.main()
