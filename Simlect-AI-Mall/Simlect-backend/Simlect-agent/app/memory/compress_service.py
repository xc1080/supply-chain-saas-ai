from __future__ import annotations

import asyncio
import json

import structlog
from langchain_core.messages import HumanMessage, SystemMessage

from app.config.settings import get_settings
from app.memory.context_builder import context_builder
from app.memory.models import SessionMemory
from app.memory.session_memory_service import session_memory_service
from app.memory.token_estimator import estimate_text_tokens
from app.services.llm_factory import create_memory_llm
from app.services.message_service import agent_message_service
from app.services.prompt_service import load_compress_prompt
from app.services.redis_service import redis_service

logger = structlog.get_logger()

def _format_turns_for_compress(turns: list[dict]) -> str:

    parts: list[str] = []
    for turn in turns:
        mid = turn.get("message_id")
        user_msg = (turn.get("user_message") or "").strip()

        assistant = (turn.get("assistant_for_history") or turn.get("assistant_message") or "").strip()
        if user_msg:
            parts.append(f"[{mid}] 用户: {user_msg}")
        if assistant:
            parts.append(f"[{mid}] 助手: {assistant}")
    return "\n".join(parts)

class CompressService:

    async def maybe_schedule_compress(
        self,
        user_id: str,
        memory: SessionMemory,
        working_turns: list[dict],
        working_oldest_id: int | None,
        user_text: str,
        system_prompt: str,
    ) -> None:

        settings = get_settings()
        estimated = context_builder.estimate_context_tokens(
            memory, working_turns, user_text, system_prompt
        )

        memory.state["estimatedTokens"] = estimated
        if estimated < settings.compress_token_threshold:
            return

        asyncio.create_task(
            self._compress_async(user_id, memory.summary_last_message_id, working_oldest_id)
        )

    async def _compress_async(
        self,
        user_id: str,
        summary_last_message_id: int,
        working_oldest_id: int | None,
    ) -> None:

        if working_oldest_id is None or working_oldest_id <= summary_last_message_id + 1:
            return

        if not await session_memory_service.try_acquire_compress_lock(user_id, redis_service.client):
            return
        try:

            memory = await session_memory_service.load(user_id, redis_service.client)
            turns = await agent_message_service.load_turns_for_memory(user_id)

            to_compress = [
                t
                for t in turns
                if summary_last_message_id < int(t["message_id"]) < working_oldest_id
            ]
            if not to_compress:
                return

            new_summary = await self._merge_summary(memory.summary, to_compress)
            memory.summary = new_summary

            memory.summary_last_message_id = int(to_compress[-1]["message_id"])
            await session_memory_service.save(memory, redis_service.client)
            logger.info(
                "session_compressed",
                user_id=user_id,
                compressed_turns=len(to_compress),
                new_boundary=memory.summary_last_message_id,
            )
        except Exception as e:
            logger.exception("session_compress_failed", user_id=user_id, error=str(e))
        finally:

            await session_memory_service.release_compress_lock(user_id, redis_service.client)

    async def _merge_summary(self, current: dict, turns: list[dict]) -> dict:

        template = await load_compress_prompt()
        chunk_text = _format_turns_for_compress(turns)

        if estimate_text_tokens(chunk_text) < 20:
            return current

        llm = create_memory_llm()
        response = await llm.ainvoke(
            [
                SystemMessage(content=template),
                HumanMessage(
                    content=(
                        "现有摘要 JSON：\n"
                        f"{json.dumps(current, ensure_ascii=False)}\n\n"
                        "待合并的新对话：\n"
                        f"{chunk_text}\n\n"
                        "请输出合并后的完整 JSON（仅 JSON，无 markdown）。"
                        "冲突时以新对话为准。"
                    )
                ),
            ]
        )

        raw = response.content if isinstance(response.content, str) else str(response.content or "")
        raw = raw.strip()

        if raw.startswith("```"):
            raw = raw.split("\n", 1)[-1].rsplit("```", 1)[0].strip()
        try:
            merged = json.loads(raw)

            if isinstance(merged, dict) and "facts" in merged:
                merged["version"] = int(current.get("version") or 1) + 1
                return merged
        except json.JSONDecodeError:
            logger.warning("compress_json_parse_failed", raw=raw[:200])

        return current

compress_service = CompressService()
