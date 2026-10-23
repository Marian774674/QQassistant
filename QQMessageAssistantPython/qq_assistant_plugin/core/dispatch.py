import asyncio
import json
import logging
from .text import human_delay_seconds

log = logging.getLogger(__name__)


def parse_answer(answer: str) -> dict:
    text = (answer or "").strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.startswith("json"):
            text = text[4:]
    try:
        data = json.loads(text)
        if isinstance(data, dict) and data.get("action"):
            return data
    except json.JSONDecodeError:
        pass
    return {"action": "SEND", "content": answer}


class Dispatcher:
    def __init__(self, spring, settings, reply):
        self._spring = spring
        self._setting = settings
        self._reply = reply

    async def handle(self, data: dict, ctx, sender_id: int, chat_id: int | None) -> str | None:
        action = data.get("action", "SILENT")

        if action == "SILENT":
            return None

        if action == "SEND":
            return await self._send(ctx, data.get("content", ""))

        if action == "DRAFT":
            draft = data.get("draft") or {}
            await self._spring.create_draft(
                draft.get("type", "REPLY"), sender_id, chat_id,
                draft.get("title"), draft.get("payload") or {}
            )
            return None

        if action == "MEMO":
            memo = data.get("memo") or {}
            await self._spring.create_memo({**memo, "senderId": sender_id, "chatId": chat_id})
            if data.get("content"):
                return await self._send(ctx, data["content"])
            return None

        if action == "FACT":
            fact = data.get("fact") or {}
            confidence = float(fact.get("confidence", 0))
            if confidence >= self._setting.fact_auto_threshold:
                await self._spring.upsert_fact(
                    sender_id,
                    fact.get("factKey"), fact.get("factValue"), confidence
                )
            else:
                await self._spring.create_draft(
                    "FACT", sender_id, chat_id, "待确认事实", fact
                )
            return None
        if action == "FILE_TASK":
            # Phase 3-2 接管；本阶段先记日志
            log.info("file task delegated: %s", data.get("file"))
            return None

        log.warning("unknown action %s, fallback to SEND", action)
        return await self._send(ctx, data.get("content", ""))

    async def _send(self, ctx, content: str) -> str | None:
        # LLM 输出常带首尾换行/空白，统一在此清洗，只剪首尾不动正文换行
        content = (content or "").strip()
        if not content:
            return None
        await asyncio.sleep(human_delay_seconds(content,self._setting))
        await self._reply(ctx,content)
        return content