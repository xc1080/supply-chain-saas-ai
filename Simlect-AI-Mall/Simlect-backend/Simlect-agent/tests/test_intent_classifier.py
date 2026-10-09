import pytest

from app.domain.intent.classifier import (
    _parse_intent_json,
    classify_intent_by_rules,
    resolve_intent,
)
from app.domain.intent.types import IntentKind

def test_parse_intent_json():
    intent, data = _parse_intent_json('{"intentType":"REFUND","data":"oid1"}')
    assert intent == IntentKind.REFUND
    assert data == "oid1"

def test_rule_fallback_refund():
    intent = classify_intent_by_rules("我要退款")
    assert intent == IntentKind.REFUND

def test_rule_chat_returns_none_without_keywords():
    assert classify_intent_by_rules("你好呀") is None

@pytest.mark.asyncio
async def test_resolve_intent_llm_primary(monkeypatch):
    async def fake_llm(*args, **kwargs):
        return IntentKind.QUERY_LOGISTICS, "oid123"

    async def fake_load():
        return "分类 %s %s"

    monkeypatch.setattr("app.domain.intent.classifier.load_user_intent_classifier_prompt", fake_load)
    monkeypatch.setattr("app.domain.intent.classifier.classify_intent_by_llm", fake_llm)

    intent, source, data = await resolve_intent("u1", "快递到哪了")
    assert intent == IntentKind.QUERY_LOGISTICS
    assert source == "llm"

@pytest.mark.asyncio
async def test_resolve_intent_structural_product_consult(monkeypatch):
    async def fail_llm(*args, **kwargs):
        raise AssertionError("LLM should not run for structural consult")

    monkeypatch.setattr("app.domain.intent.classifier.classify_intent_by_llm", fail_llm)
    consult = {"productId": "1", "productName": "FG800"}
    intent, source, _ = await resolve_intent(
        "u1",
        "这款内存多大",
        from_product=True,
        consult_card=consult,
    )
    assert intent == IntentKind.PRODUCT_CONSULT
    assert source == "structural"
