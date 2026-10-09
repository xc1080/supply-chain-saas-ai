from __future__ import annotations

import asyncio

import structlog
from langchain_core.messages import HumanMessage, SystemMessage

from app.config.settings import get_settings
from app.services.llm_factory import create_memory_llm
from app.services.redis_service import redis_service

logger = structlog.get_logger()
_CONDENSE_SYSTEM = (
    "你是文本压缩助手。将用户给出的客服回复压缩到 500 字以内，"
    "保留关键信息（商品名、价格、订单号、操作结论）。"
    "直接输出压缩后的正文，不要道歉，不要说明被压缩过，不要加前缀。"
)

_pending_condense: set[str] = set()

def truncate_assistant_for_history(text: str | None, max_len: int | None = None) -> str:

    settings = get_settings()
    limit = max_len or settings.assistant_history_max_len
    stripped = (text or "").strip()
    if not stripped:
        return ""
    return stripped if len(stripped) <= limit else stripped[:limit]

async def condense_assistant_for_history(text: str, max_len: int | None = None) -> str:

    settings = get_settings()
    limit = max_len or settings.assistant_history_max_len
    stripped = (text or "").strip()
    if not stripped or len(stripped) <= limit:
        return stripped

    try:
        llm = create_memory_llm()
        response = await llm.ainvoke(
            [
                SystemMessage(content=_CONDENSE_SYSTEM),
                HumanMessage(
                    content=f"原文字数 {len(stripped)}，请压缩到 {limit} 字以内：\n\n{stripped}"
                ),
            ]
        )
        content = response.content if isinstance(response.content, str) else str(response.content or "")
        condensed = content.strip()
        if condensed and len(condensed) <= limit + 50:
            return condensed[:limit]
    except Exception as e:
        logger.warning("assistant_condense_failed", error=str(e))

    return stripped[:limit]

def schedule_assistant_condense(user_id: str, message_id: int, text: str | None) -> None:

    stripped = (text or "").strip()
    if not stripped:
        return
    settings = get_settings()
    if len(stripped) <= settings.assistant_history_max_len:
        return

    key = f"{user_id}:{message_id}"
    if key in _pending_condense:
        return
    _pending_condense.add(key)
    asyncio.create_task(_run_assistant_condense(key, user_id, message_id, stripped))

async def _run_assistant_condense(key: str, user_id: str, message_id: int, text: str) -> None:
    try:
        condensed = await condense_assistant_for_history(text)
        if condensed:
            await redis_service.save_history_condensed(user_id, message_id, condensed)
            logger.info(
                "assistant_condense_done",
                user_id=user_id,
                message_id=message_id,
                src_len=len(text),
                out_len=len(condensed),
            )
    except Exception as e:
        logger.warning("assistant_condense_async_failed", user_id=user_id, message_id=message_id, error=str(e))
    finally:
        _pending_condense.discard(key)
