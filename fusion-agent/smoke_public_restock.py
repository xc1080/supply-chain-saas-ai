"""A normal customer arrival question must not invoke merchant procurement."""
import asyncio
import json
from pathlib import Path
import time
import httpx
from smoke_business_planning import api, STORE


async def run():
    async with httpx.AsyncClient(timeout=100, trust_env=False) as buyer:
        await api(buyer, "account/autoLogin")
        sent = await api(buyer, "agent/sendMessage", message="T300什么时候补货到货？")
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            history = await api(buyer, "agent/loadHistoryMessage", pageSize=20)
            row = next((r for r in history["list"] if r["messageId"] == sent["messageId"]), None)
            if row and row["status"] != 1:
                assert row["status"] == 2, row
                card = json.loads(row["assistantMessage"])
                answer = card.get("answer") or card["intro"]
                assert "商家核实" in answer and "没有" in answer, card
                assert all(step["step"] != "plan_replenishment" for step in card.get("trace", [])), card
                result = {"status": "PASSED", "answer": answer, "merchantToolUsed": False, "arrivalDateInvented": False}
                out = Path(__file__).parent.parent / "logs" / "public-restock-live-result.json"
                out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
                print(json.dumps(result, ensure_ascii=False)); return
            await asyncio.sleep(.4)
        raise AssertionError("Customer restock query timed out")


if __name__ == "__main__": asyncio.run(run())
