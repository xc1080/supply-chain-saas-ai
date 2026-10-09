"""Business constraints and authentication checks; no live model requests."""

import asyncio
import os
import unittest
import tempfile
from unittest.mock import AsyncMock, patch

import httpx
from fastapi import HTTPException
from fastapi.testclient import TestClient

import main
from retrieval import normalize_products, rank_products, resolve_query, rrf_merge


PRODUCTS = [
    {"id": "zb", "code": "DEMO-ZB", "name": "卧室智能灯", "category": "照明", "spec": "Zigbee 调光", "remark": "需要 Zigbee 网关", "price": 129.0, "stock": 36.0},
    {"id": "wifi", "code": "DEMO-WIFI", "name": "WiFi 智能台灯", "category": "照明", "spec": "Wi-Fi 直连", "remark": "无需 Zigbee 网关", "price": 199.0, "stock": 18.0},
    {"id": "ceiling", "code": "DEMO-CEILING", "name": "客厅吸顶灯", "category": "照明", "spec": "WiFi 调光", "remark": "适合客厅", "price": 499.0, "stock": 8.0},
    {"id": "sensor", "code": "DEMO-SENSOR", "name": "门窗传感器", "category": "传感器", "spec": "Zigbee", "remark": "搭配网关", "price": 69.0, "stock": 50.0},
    {"id": "gateway", "code": "DEMO-GATEWAY", "name": "智能网关", "category": "网关", "spec": "Zigbee", "remark": "连接 Zigbee 传感器和灯", "price": 199.0, "stock": 12.0},
    {"id": "switch", "code": "DEMO-SWITCH", "name": "智能开关", "category": "开关", "spec": "Zigbee", "remark": "缺货", "price": 79.0, "stock": 0.0},
]


class RetrievalTests(unittest.TestCase):
    def test_rrf_is_actual_upstream_module(self):
        self.assertEqual(rrf_merge.__module__, "app.rag.rrf")
        self.assertEqual(rrf_merge(["a", "b"], ["b", "c"], 2)[0], "b")

    def test_budget_protocol_and_stock_are_hard_constraints(self):
        result = rank_products("推荐200元以内的Zigbee智能灯", PRODUCTS, {p["id"]: 0.99 for p in PRODUCTS})
        self.assertEqual([p["id"] for p in result], ["zb"])

    def test_exact_frontend_suggestion_spaces_and_target(self):
        result = rank_products("卧室 300 元以内的智能灯怎么选？", PRODUCTS, {p["id"]: 0.99 for p in PRODUCTS})
        self.assertEqual({p["id"] for p in result}, {"zb", "wifi"})

    def test_existing_gateway_is_context_not_target(self):
        result = rank_products("我有Zigbee网关，适合选哪些传感器", PRODUCTS)
        self.assertEqual([p["id"] for p in result], ["sensor"])

    def test_existing_gateway_at_end_is_context_not_target(self):
        result = rank_products("卧室智能灯怎么选？我已经有 Zigbee 网关。", PRODUCTS, {p["id"]: 0.99 for p in PRODUCTS})
        self.assertEqual({p["id"] for p in result}, {"zb", "wifi", "ceiling"})

    def test_absent_named_gateway_does_not_require_its_protocol(self):
        result = rank_products("预算200元，我没有 Zigbee 网关，推荐灯", PRODUCTS)
        self.assertEqual([p["id"] for p in result], ["wifi"])

    def test_gateway_in_remark_does_not_change_protocol(self):
        result = rank_products("推荐Zigbee灯", PRODUCTS)
        self.assertEqual([p["id"] for p in result], ["zb"])

    def test_no_gateway_matches_wifi_description(self):
        result = rank_products("200以内推荐不用网关的灯", PRODUCTS)
        self.assertEqual([p["id"] for p in result], ["wifi"])

    def test_no_existing_gateway_is_context_not_a_requested_product(self):
        result = rank_products("卧室用，预算200元，没有网关，推荐一款灯，为什么？", PRODUCTS, {p["id"]: 0.99 for p in PRODUCTS})
        self.assertEqual([p["id"] for p in result], ["wifi"])

    def test_zero_stock_can_be_queried_but_never_recommended(self):
        self.assertEqual(rank_products("推荐智能开关", PRODUCTS), [])
        self.assertEqual([p["id"] for p in rank_products("查智能开关库存", PRODUCTS)], ["switch"])

    def test_unrelated_goods_are_not_used_to_fill_empty_result(self):
        self.assertEqual(rank_products("推荐手机", PRODUCTS, {p["id"]: 0.99 for p in PRODUCTS}), [])

    def test_semantic_candidate_is_independent_of_keyword_candidates(self):
        result = rank_products("气氛随心变幻", PRODUCTS, {"zb": 0.91, "wifi": 0.1})
        self.assertEqual([p["id"] for p in result], ["zb"])

    def test_followup_inherits_topic_but_replaces_budget(self):
        query, constraints, inherited = resolve_query("那200以内呢", [{"role": "user", "content": "推荐500元以内的智能灯"}])
        self.assertTrue(inherited)
        self.assertEqual(constraints["max_price"], 200)
        self.assertEqual({p["id"] for p in rank_products(query, PRODUCTS, constraints=constraints)}, {"zb", "wifi"})

    def test_followup_replaces_protocol(self):
        query, constraints, _ = resolve_query("那WiFi呢", [{"role": "user", "content": "推荐Zigbee智能灯"}])
        self.assertEqual(constraints["protocols"], ["wifi"])
        self.assertEqual({p["id"] for p in rank_products(query, PRODUCTS, constraints=constraints)}, {"wifi", "ceiling"})

    def test_inventory_rows_are_authoritative_and_summed(self):
        rows = [{"productId": "x", "productName": "智能灯", "status": "0", "univalence": "129", "inventoryQty": "9999"}]
        inventory = [{"productId": "x", "planQuantity": "3"}, {"product": {"productId": "x"}, "planQuantity": "4"}]
        self.assertEqual(normalize_products(rows, inventory)[0]["stock"], 7)


class ApiTests(unittest.TestCase):
    def test_missing_or_malformed_token_is_unauthorized(self):
        with TestClient(main.app) as client:
            self.assertEqual(client.post("/chat", json={"message": "推荐灯"}).status_code, 401)
            self.assertEqual(client.post("/chat", headers={"Authorization": "Basic hello"}, json={"message": "推荐灯"}).status_code, 401)

    def test_java_auth_error_is_propagated(self):
        async def check(status):
            transport = httpx.MockTransport(lambda request: httpx.Response(200, json={"code": status}))
            async with httpx.AsyncClient(transport=transport) as client:
                with self.assertRaises(HTTPException) as caught:
                    await main.java_rows(client, "/test", "Bearer fake")
                self.assertEqual(caught.exception.status_code, status)
        asyncio.run(check(401))
        asyncio.run(check(403))

    def test_bounded_flow_without_model_keys(self):
        async def fake_rows(client, path, authorization):
            self.assertEqual(authorization, "Bearer fake")
            if path.startswith("/baseDate"):
                return [{"productId": "x", "productCode": "X", "productName": "智能灯", "productSpecifications": "Zigbee", "univalence": "129", "status": "0"}]
            return [{"productId": "x", "planQuantity": "36"}]
        no_keys = {key: "" for key in ("FUSION_LLM_KEY", "DEEPSEEK_API_KEY", "AI_BAILIAN_API_KEY", "FUSION_EMBEDDING_KEY")}
        async def identity(authorization): return {"tenantId":"demo","userId":1}
        async def catalog(state): return await main.fetch_data(state)
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ,{**no_keys,"FUSION_AGENT_DB":temporary+"/runs.sqlite3"}), patch.object(main,"agent_identity",identity), patch.object(main,"workspace_catalog",catalog), patch.object(main, "java_rows", fake_rows), TestClient(main.app) as client:
            response = client.post("/chat", headers={"Authorization": "Bearer fake"}, json={"message": "推荐200以内的Zigbee灯"})
            self.assertEqual(response.status_code, 200)
            body = response.json()
            self.assertEqual(body["mode"], {"llm": "local", "retrieval": "local", "model": None,"agent":"bounded_tools","planning":"rules"})
            self.assertEqual(body["products"][0]["stock"], 36)
            self.assertEqual(body["products"][0]["price"], 129)
            self.assertNotIn("129 元", body["answer"])
            self.assertIn("P-x", body["citations"])
            self.assertNotIn("[P-x]", body["answer"])
            self.assertEqual([item["step"] for item in body["trace"] if item["step"] in ("fetch_data", "retrieve", "answer")], ["fetch_data", "retrieve", "answer"])
            self.assertNotIn("fake", str(body))

    def test_fabricated_model_number_is_discarded(self):
        async def call():
            state = {"message": "推荐智能灯", "products": [PRODUCTS[0]], "sources": [{"id": "P-zb", "title": "灯", "content": "库存36"}], "trace": [], "mode": {"llm": "local", "retrieval": "local", "model": None}}
            response = httpx.Response(200, json={"choices": [{"message": {"content": '{"answer":"库存有999台。","citations":["P-zb"]}'}}]}, request=httpx.Request("POST", "https://test.invalid"))
            mock_client = AsyncMock()
            mock_client.__aenter__.return_value.post.return_value = response
            with patch.dict(os.environ, {"FUSION_LLM_KEY": "test-only"}), patch.object(main.httpx, "AsyncClient", return_value=mock_client):
                result = await main.answer(state)
            self.assertNotIn("999", result["answer"])
            self.assertEqual(result["mode"]["llm"], "local")
        asyncio.run(call())

    def test_known_source_label_is_not_mistaken_for_live_quantity(self):
        async def call():
            product = dict(PRODUCTS[0], id="1")
            state = {"message": "推荐智能灯", "products": [product], "sources": [{"id": "P-1", "title": "灯", "content": "规格 Zigbee"}], "trace": [], "mode": {"llm": "local", "retrieval": "hybrid", "model": None}}
            response = httpx.Response(200, json={"choices": [{"message": {"content": '{"answer":"P-1需搭配兼容网关使用。","citations":["P-1"]}'}}]}, request=httpx.Request("POST", "https://test.invalid"))
            mock_client = AsyncMock()
            mock_client.__aenter__.return_value.post.return_value = response
            with patch.dict(os.environ, {"FUSION_LLM_KEY": "test-only"}), patch.object(main.httpx, "AsyncClient", return_value=mock_client):
                result = await main.answer(state)
            self.assertEqual(result["mode"]["llm"], "online")
            self.assertIn("卧室智能灯需搭配", result["answer"])
        asyncio.run(call())


if __name__ == "__main__":
    unittest.main(verbosity=2)
