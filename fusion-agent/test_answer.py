"""Answer presentation and live-fact guards, without external model calls."""

import asyncio
import json
import os
import unittest
from unittest.mock import AsyncMock, patch

import httpx

import main


PRODUCT = {"id": "1", "code": "DEMO-LAMP-ZB", "name": "卧室柔光智能灯", "spec": "Zigbee/9W", "remark": "卧室柔光、可调亮度；需兼容网关", "price": 129.0, "stock": 36.0}


def make_state(message="卧室智能灯怎么选？"):
    return {"message": message, "history": [], "products": [PRODUCT], "sources": [{"id": "P-1", "title": "卧室柔光智能灯", "content": "规格 Zigbee/9W"}, {"id": "DEMO-PROTOCOL", "title": "设备兼容", "content": "Zigbee 需要兼容网关。"}], "trace": [], "mode": {"llm": "local", "retrieval": "hybrid", "model": None}}


def mocked_answer(payload, state=None):
    async def call():
        response = httpx.Response(200, json={"choices": [{"message": {"content": json.dumps(payload, ensure_ascii=False)}}]}, request=httpx.Request("POST", "https://test.invalid"))
        mock_client = AsyncMock()
        mock_client.__aenter__.return_value.post.return_value = response
        with patch.dict(os.environ, {"FUSION_LLM_KEY": "test-only"}), patch.object(main.httpx, "AsyncClient", return_value=mock_client):
            result = await main.answer(state or make_state())
            request = mock_client.__aenter__.return_value.post.call_args_list[0].kwargs["json"]
        return result, request
    return asyncio.run(call())


class AnswerTests(unittest.TestCase):
    def test_invalid_model_reply_is_repaired_using_the_same_business_evidence(self):
        state = make_state("卧室柔光智能灯多少钱？")
        async def run():
            outputs = [{"answer": "卧室柔光智能灯售价999元。", "citations": ["P-1"]}, {"answer": "卧室柔光智能灯参考售价为 {{price:P-1}}。", "citations": ["P-1"]}]
            client = AsyncMock()
            client.__aenter__.return_value.post.side_effect = [httpx.Response(200, json={"choices": [{"message": {"content": json.dumps(output, ensure_ascii=False)}}]}, request=httpx.Request("POST", "https://test.invalid")) for output in outputs]
            with patch.dict(os.environ, {"FUSION_LLM_KEY": "test-only"}), patch.object(main.httpx, "AsyncClient", return_value=client):
                result = await main.answer(state)
            self.assertEqual(client.__aenter__.return_value.post.await_count, 2)
            self.assertEqual(result["mode"]["llm"], "online")
            self.assertIn("129 元", result["answer"])
            self.assertNotIn("999", result["answer"])
            self.assertTrue(any(step["step"] == "answer_validation" for step in result["trace"]))
        asyncio.run(run())

    def test_full_answer_is_not_appended_to_a_second_product_list(self):
        prose = "卧室柔光智能灯支持调亮度，已有兼容 Zigbee 网关时可以考虑。\n\n你已经有网关了吗？"
        result, _ = mocked_answer({"answer": prose, "citations": ["P-1", "DEMO-PROTOCOL"]})
        self.assertEqual(result["mode"]["llm"], "online")
        self.assertEqual(result["answer"], prose)
        self.assertNotIn("planQuantity", result["answer"])
        self.assertNotIn("[P-1]", result["answer"])

    def test_exact_live_fact_slots_are_rendered_from_current_java_data(self):
        result, _ = mocked_answer({"answer": "卧室柔光智能灯参考售价为 {{price:P-1}}，本次查询的账面库存为 {{stock:P-1}}。", "citations": ["P-1"]}, make_state("卧室柔光智能灯多少钱，还有多少库存？"))
        self.assertEqual(result["mode"]["llm"], "online")
        self.assertIn("129 元", result["answer"])
        self.assertIn("36", result["answer"])
        self.assertNotIn("{{", result["answer"])

    def test_unknown_fact_slot_is_rejected(self):
        result, _ = mocked_answer({"answer": "售价为 {{price:P-999}}。", "citations": ["P-1"]})
        self.assertEqual(result["mode"]["llm"], "local")
        self.assertNotIn("999", result["answer"])

    def test_unsupported_compatibility_lookup_promise_is_rejected(self):
        prose = "需要我帮您查一下常见 Zigbee 网关的兼容清单，或确认这款灯是否适配您的具体网关型号吗？"
        result, _ = mocked_answer({"answer": prose, "citations": ["P-1"]})
        self.assertEqual(result["mode"]["llm"], "local")
        self.assertNotIn("我帮您查", result["answer"])

    def test_protocol_does_not_prove_unlisted_app_or_remote_features(self):
        for prose in ("它通常支持手机App控制与基础远程操作。", "它通常可直接通过手机App控制。", "它即连即用，无需配置。"):
            result, _ = mocked_answer({"answer": prose, "citations": ["P-1"]})
            self.assertEqual(result["mode"]["llm"], "local")
        result, _ = mocked_answer({"answer": "是否支持手机App控制，需要核对厂家说明。", "citations": ["P-1"]})
        self.assertEqual(result["mode"]["llm"], "online")

    def test_recommendation_does_not_send_unrelated_order_context(self):
        state = make_state("推荐卧室灯")
        state["context"] = {"channel": "customer", "orders": [{"id": "private-order", "orderNo": "private-order"}]}
        _, request = mocked_answer({"answer": "可以核对灯具规格与网关兼容性。", "citations": ["P-1"]}, state)
        self.assertNotIn("private-order", json.dumps(request))

    def test_fabricated_raw_number_is_rejected(self):
        result, _ = mocked_answer({"answer": "卧室柔光智能灯库存有 999 台。", "citations": ["P-1"]})
        self.assertEqual(result["mode"]["llm"], "local")
        self.assertNotIn("999", result["answer"])

    def test_exact_user_budget_is_allowed_only_in_budget_context(self):
        state = make_state("卧室用，预算200元，没有网关，推荐一款灯，为什么？")
        for prose in ("你的预算200元，可以先核对协议与网关需求。", "在您200元预算范围内，可以先核对协议与网关需求。", "在200元以内，可以核对灯具与网关需求。"):
            result, _ = mocked_answer({"answer": prose, "citations": ["P-1"]}, state)
            self.assertEqual(result["mode"]["llm"], "online")
        for prose in ("预算999元，可以选它。", "卧室柔光智能灯库存200台。", "卧室柔光智能灯售价200元。"):
            result, _ = mocked_answer({"answer": prose, "citations": ["P-1"]}, state)
            self.assertEqual(result["mode"]["llm"], "local")

    def test_unrelated_or_fake_citation_is_rejected(self):
        result, _ = mocked_answer({"answer": "这款支持调光。", "citations": ["P-999"]})
        self.assertEqual(result["mode"]["llm"], "local")

    def test_write_completion_claim_is_rejected(self):
        result, _ = mocked_answer({"answer": "已为您完成出库。", "citations": ["P-1"]})
        self.assertEqual(result["mode"]["llm"], "local")
        self.assertNotIn("已为您完成出库", result["answer"])

    def test_known_spec_number_is_allowed_without_loosening_live_fact_guard(self):
        result, _ = mocked_answer({"answer": "卧室柔光智能灯的规格是 Zigbee/9W，需核对网关兼容性。", "citations": ["P-1"]})
        self.assertEqual(result["mode"]["llm"], "online")
        self.assertIn("Zigbee/9W", result["answer"])

    def test_history_is_supplied_and_contact_details_are_redacted(self):
        state = make_state("那不用网关呢？")
        state["history"] = [{"role": "user", "content": "我想给卧室选灯，手机号 13800138000"}, {"role": "assistant", "content": "已有网关吗？"}]
        _, request = mocked_answer({"answer": "如果不想加网关，可以选择标明直连的型号。", "citations": ["DEMO-PROTOCOL"]}, state)
        serialized = json.dumps(request, ensure_ascii=False)
        self.assertIn("卧室选灯", serialized)
        self.assertNotIn("13800138000", serialized)

    def test_local_fallback_is_short_and_does_not_dump_raw_knowledge(self):
        state = make_state()
        keys = {name: "" for name in ("FUSION_LLM_KEY", "AI_BAILIAN_API_KEY", "DEEPSEEK_API_KEY")}
        with patch.dict(os.environ, keys):
            result = asyncio.run(main.answer(state))
        self.assertEqual(result["mode"]["llm"], "local")
        self.assertLess(len(result["answer"]), 400)
        self.assertNotIn("planQuantity", result["answer"])
        self.assertNotIn("[DEMO-", result["answer"])
        self.assertNotIn("参考售价 129", result["answer"])

    def test_current_customer_order_facts_are_resolved_without_contacts(self):
        state = make_state("我的订单现在是什么状态，金额多少？")
        state["products"] = []
        state["context"] = {"channel": "customer", "orders": [{"id": "demo-order", "orderNo": "DEMO2026100801", "status": "UNPAID", "totalAmount": 258, "phone": "13800138000", "items": [{"buyCount": 2}]}]}
        result, request = mocked_answer({"answer": "演示订单 {{order_no:O-demo-order}} 当前待支付，金额为 {{order_total:O-demo-order}}。尚未接入真实支付。", "citations": ["O-demo-order"]}, state)
        self.assertEqual(result["mode"]["llm"], "online")
        self.assertIn("DEMO2026100801", result["answer"])
        self.assertIn("258 元", result["answer"])
        self.assertNotIn("13800138000", json.dumps(request))
        self.assertEqual(main.fact_slots(state)["{{order_quantity:O-demo-order}}"], "2")

    def test_service_question_does_not_show_unrelated_product_cards(self):
        state = make_state("我的订单怎么退款？")
        state["catalog"] = [PRODUCT]
        state["context"] = {"channel": "customer", "product_id": "1"}
        with patch.dict(os.environ, {"FUSION_EMBEDDING_KEY": "", "AI_BAILIAN_API_KEY": ""}):
            result = asyncio.run(main.retrieve(state))
        self.assertEqual(result["products"], [])

    def test_product_detail_context_uses_current_product_without_search_drift(self):
        state = make_state("这款的规格是什么？")
        state["catalog"] = [PRODUCT]
        state["context"] = {"channel": "customer", "product_id": "1"}
        with patch.dict(os.environ, {"FUSION_EMBEDDING_KEY": "", "AI_BAILIAN_API_KEY": ""}):
            result = asyncio.run(main.retrieve(state))
        self.assertEqual(result["products"], [PRODUCT])

    def test_customer_shipping_is_not_invented(self):
        state = make_state("订单到哪里了？")
        state["products"] = []
        state["context"] = {"channel": "customer", "orders": [{"id": "demo-order", "status": "UNPAID"}]}
        result, _ = mocked_answer({"answer": "您的订单已发货，运输中。", "citations": ["O-demo-order"]}, state)
        self.assertEqual(result["mode"]["llm"], "local")
        self.assertNotIn("已发货", result["answer"])

    def test_order_contents_use_only_whitelisted_items_and_fact_quantities(self):
        state = make_state("我的订单包含什么？")
        state["products"] = []
        state["context"] = {"channel": "customer", "orders": [{"id": "owned", "status": "UNPAID", "items": [{"productName": "卧室柔光智能灯", "propertyInfo": "Zigbee/9W", "buyCount": 2, "remark": "机密备注", "phone": "13800138000"}], "address": "机密地址"}]}
        result, request = mocked_answer({"answer": "订单包含卧室柔光智能灯，规格 Zigbee/9W，数量为 {{order_item_quantity:O-owned:0}}。", "citations": ["O-owned"]}, state)
        self.assertEqual(result["mode"]["llm"], "online")
        self.assertIn("数量为 2", result["answer"])
        serialized = json.dumps(request, ensure_ascii=False)
        self.assertIn("卧室柔光智能灯", serialized)
        self.assertNotIn("机密备注", serialized)
        self.assertNotIn("机密地址", serialized)
        self.assertNotIn("13800138000", serialized)

    def test_exact_order_quantity_is_accepted_only_for_the_cited_item(self):
        state = make_state("我的订单里买了什么，现在是什么状态，总额多少？")
        state["products"] = []
        state["context"] = {"channel": "customer", "orders": [{"id": "owned", "status": "UNPAID", "totalAmount": 258, "items": [{"productName": "卧室柔光智能灯", "propertyInfo": "Zigbee/9W", "buyCount": 2}]}]}
        prose = "订单包含 2 件卧室柔光智能灯，状态为 {{order_status:O-owned}}，总额为 {{order_total:O-owned}}。"
        result, request = mocked_answer({"answer": prose, "citations": ["O-owned"]}, state)
        self.assertEqual(result["mode"]["llm"], "online")
        self.assertIn("2 件卧室柔光智能灯", result["answer"])
        self.assertIn("258 元", result["answer"])
        self.assertEqual(json.loads(request["messages"][-1]["content"])["orders"][0]["status"], "{{order_status:O-owned}}")
        for payload in ({"answer": prose.replace("2 件", "3 件"), "citations": ["O-owned"]}, {"answer": prose, "citations": []}):
            result, _ = mocked_answer(payload, state)
            self.assertEqual(result["mode"]["llm"], "local")

    def test_order_fallback_answers_contents_status_and_total_together(self):
        state = make_state("我的订单里买了什么，现在是什么状态，总额多少？")
        state["products"] = []
        state["context"] = {"channel": "customer", "orders": [{"id": "owned", "status": "UNPAID", "totalAmount": 258, "items": [{"productName": "卧室柔光智能灯", "propertyInfo": "Zigbee/9W", "buyCount": 2}]}]}
        result = main.deterministic_answer(state)
        self.assertIn("卧室柔光智能灯", result)
        self.assertIn("待支付", result)
        self.assertIn("258 元", result)

    def test_order_status_is_resolved_only_from_current_business_state(self):
        for status, expected in (("PAID", "待发货"), ("SHIPPED", "演示发货"), ("RECEIVED", "演示收货"), ("LEGACY", "历史演示单")):
            with self.subTest(status=status):
                state = make_state("我的订单状态怎么样？")
                state["products"] = []
                state["context"] = {"channel": "customer", "orders": [{"id": "owned", "status": status, "items": []}]}
                result, _ = mocked_answer({"answer": "这笔订单的状态为 {{order_status:O-owned}}。", "citations": ["O-owned"]}, state)
                self.assertEqual(result["mode"]["llm"], "online")
                self.assertIn(expected, result["answer"])
                self.assertNotIn("{{", result["answer"])

    def test_ambiguous_quantities_across_orders_are_not_guessed(self):
        state = make_state("我的订单包含什么？")
        state["products"] = []
        state["context"] = {"channel": "customer", "orders": [{"id": "a", "status": "UNPAID", "items": [{"productName": "智能灯", "buyCount": 2}]}, {"id": "b", "status": "UNPAID", "items": [{"productName": "智能灯", "buyCount": 3}]}]}
        result, _ = mocked_answer({"answer": "订单包含 2 件智能灯。", "citations": ["O-a", "O-b"]}, state)
        self.assertEqual(result["mode"]["llm"], "local")


if __name__ == "__main__":
    unittest.main(verbosity=2)
