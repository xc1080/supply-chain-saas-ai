from app.harness.guardrails.output_guard import OutputGuardrail, strip_emojis
from app.harness.guardrails.tool_guard import ToolGuardrail

def test_output_guard_act_token():
    guard = OutputGuardrail()
    valid = "请确认操作【act_" + "a" * 32 + "】"
    tokens = guard.extract_action_tokens(valid)
    assert len(tokens) == 1
    assert guard.extract_action_tokens("【act_propose_product_review】") == []

def test_strip_emojis():
    text = "抱歉没有找到哦～😅 请看下方推荐👇"
    cleaned = strip_emojis(text)
    assert "😅" not in cleaned
    assert "👇" not in cleaned
    assert "抱歉没有找到哦" in cleaned

def test_validate_no_false_order_capability():
    guard = OutputGuardrail()
    text = "请提供你的收货地址和联系方式，我来帮你处理下单事宜～"
    out = guard.validate_no_false_capability(text, [])
    assert "自行完成下单" in out

def test_tool_guard_write():
    guard = ToolGuardrail()
    assert guard.is_write_tool("PROPOSE_REFUND")
    assert not guard.is_write_tool("QUERY_LOGISTICS")
    assert guard.is_allowed("SEARCH_PRODUCTS")
    assert guard.is_allowed("QUERY_ORDERS")

def test_rrf_merge():
    from app.rag.rrf import rrf_merge

    merged = rrf_merge(["a", "b"], ["b", "c"], 2)
    assert "b" in merged
    assert len(merged) == 2
