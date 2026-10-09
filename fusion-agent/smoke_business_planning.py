"""Live HTTP verification with isolated visitors; adds a reviewed bundle to cart.

No stock/order/payment writes, no credential output, no mocked business facts.
Run after the new Java/Python services and scenario catalog are ready.
"""
from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
import time

import httpx

STORE = os.getenv("FUSION_SMOKE_STORE", "http://127.0.0.1:7050").rstrip("/")
JAVA = os.getenv("FUSION_JAVA_URL", "http://127.0.0.1:8035").rstrip("/")
RESULT_PATH = Path(__file__).resolve().parent.parent / "logs" / "business-planning-smoke-result.json"


async def api(client, path, *, expected=200, **body):
    response = await client.post(STORE + "/api/" + path, json=body)
    response.raise_for_status()
    data = response.json()
    assert data["code"] == expected, (path, data)
    return data.get("data")


async def ask(client, question):
    message = await api(client, "agent/sendMessage", message=question)
    deadline = time.monotonic() + 110
    while time.monotonic() < deadline:
        history = await api(client, "agent/loadHistoryMessage", pageSize=50)
        row = next((row for row in history["list"] if row["messageId"] == message["messageId"]), None)
        if row and row["status"] != 1:
            assert row["status"] == 2, ("Agent run failed", row)
            card = json.loads(row["assistantMessage"])
            assert card.get("businessPlan"), card
            return card
        await asyncio.sleep(0.4)
    raise AssertionError("Live Agent did not finish within its bounded time budget")


async def main():
    timeout = httpx.Timeout(100, connect=5)
    async with httpx.AsyncClient(timeout=timeout, trust_env=False) as alice, httpx.AsyncClient(timeout=timeout, trust_env=False) as bob:
        await api(alice, "account/autoLogin")
        await api(bob, "account/autoLogin")
        catalog = (await api(alice, "product/loadProduct", pageSize=50))["list"]
        by_code = {product["productCode"]: product for product in catalog}
        assert all(code in by_code for code in ("LAB-TAPO-H100", "LAB-TAPO-T100", "LAB-TAPO-T300"))
        initial = await ask(alice, "卧室配齐智能家居，总预算1000元")
        assert initial["businessPlan"]["status"] == "NEEDS_INPUT"
        card = await ask(alice, "英国地区已核对电源频段和安装，一套1个H100网关和2个T100人体传感器")
        plan = card["businessPlan"]
        assert plan["status"] == "READY_FOR_REVIEW", plan
        assert plan["compatibility"]["status"] == "COMPATIBLE"
        assert plan["delivery"]["status"] == "UNKNOWN" and not plan["delivery"]["promise"]
        expected_ids = {str(by_code[code]["productId"]): quantity for code, quantity in (("LAB-TAPO-H100", 1), ("LAB-TAPO-T100", 2))}
        assert {item["productId"]: item["quantity"] for item in plan["items"]} == expected_ids
        assert plan["budget"]["total"] == sum(item["unitPrice"] * item["quantity"] * plan["units"] for item in plan["items"])
        await api(bob, "agent/quoteDetail", quoteToken=plan["quoteToken"], expected=404)
        confirmed = (await api(alice, "agent/confirmQuote", quoteToken=plan["quoteToken"], revision=plan["revision"]))["businessPlan"]
        assert confirmed["confirmationStatus"] == "ADDED", confirmed
        await api(alice, "agent/confirmQuote", quoteToken=plan["quoteToken"], revision=plan["revision"])
        cart = (await api(alice, "productCart/loadCart"))["list"]
        assert {str(item["productId"]): item["buyCount"] for item in cart} == expected_ids, cart

        stockout = await ask(bob, "生成整套报价，一套1个T300漏水传感器，我已有H100网关，总预算1000元，英国地区适用，已确认安装条件")
        unavailable = stockout["businessPlan"]
        assert unavailable["status"] != "READY_FOR_REVIEW"
        assert unavailable["inventory"]["shortages"]
        assert unavailable["alternatives"] == [], "A temperature sensor must not substitute for a leak sensor"
        await api(bob, "agent/confirmQuote", quoteToken=unavailable["quoteToken"], revision=unavailable["revision"], expected=409)

        platform = await ask(bob, "生成整套报价，一套1个T100人体传感器，我已有H100网关，总预算1000元，英国地区适用，已确认安装条件，需要HomeKit")
        uncertain = platform["businessPlan"]
        assert uncertain["status"] == "NEEDS_INPUT"
        assert any(item["field"] == "platformVerification" for item in uncertain["missing"])

        login = await alice.post(JAVA + "/login", json={"username": os.getenv("FUSION_SMOKE_USER", "admin"), "password": os.getenv("FUSION_SMOKE_PASSWORD", "admin123")})
        login.raise_for_status()
        token = login.json()["token"]
        merchant = await alice.post(STORE + "/chat", headers={"Authorization": "Bearer " + token}, json={"message": "读取当前店铺销量、占用、在途和交期，生成备货草稿"})
        merchant.raise_for_status()
        merchant_plan = merchant.json()["businessPlan"]
        assert merchant_plan["type"] == "MERCHANT_REPLENISHMENT" and not merchant_plan["executable"]
        assert not any(secret in json.dumps(merchant_plan) for secret in ("costPrice", "supplierPhone", "supplierEmail"))
        result = {"status": "PASSED", "bundle": plan, "cartAddedExactlyOnce": expected_ids,
                  "crossVisitorQuoteRejected": True, "stockoutNoUnrelatedReplacement": unavailable,
                  "platformEvidenceRequired": uncertain, "merchantDraft": merchant_plan}
        RESULT_PATH.parent.mkdir(parents=True, exist_ok=True)
        RESULT_PATH.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"status": "PASSED", "bundleTotal": plan["budget"]["total"], "bundleRun": card["runId"],
            "stockoutStatus": unavailable["status"], "platformStatus": uncertain["status"],
            "merchantItems": len(merchant_plan["items"]), "resultPath": str(RESULT_PATH)}, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())
