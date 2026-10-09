import pytest

from app.domain.intent.types import IntentKind
from app.services.prompt_service import _safe_format, build_agent_system_prompt

@pytest.mark.asyncio
async def test_build_agent_system_prompt_includes_global_and_intent(monkeypatch):
    async def fake_load(key: str) -> str:
        data = {
            "global": "GLOBAL_RULES",
            "refund": "退款指引 user=%s q=%s",
        }
        return data.get(key, "")

    monkeypatch.setattr("app.services.prompt_service.load_prompt", fake_load)
    text = await build_agent_system_prompt(IntentKind.REFUND, "u1", "我要退款")
    assert "GLOBAL_RULES" in text
    assert "REFUND" in text or "退款指引" in text
    assert "u1" in text
    assert "ReAct" in text or "SEARCH_PRODUCTS" in text
