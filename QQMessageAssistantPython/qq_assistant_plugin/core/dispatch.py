import asyncio
import json
import logging

from . import file_store
from .text import human_delay_seconds

log = logging.getLogger(__name__)

# Chatflow 允许的动作；不在这张表里的输出一律按 SILENT 处理（宁可不回，也不发错）
KNOWN_ACTIONS = {
    "SEND", "DRAFT", "SILENT", "MEMO", "FACT", "MEMO_CLOSE",
    "WAIT_FILE", "ASK", "FILE_FILL",
}

# 与 Spring 侧 MemoServiceImpl.CATEGORIES 保持一致；模型写错枚举时兜底成「其他」，
# 否则 /api/memos 会 400，整条备忘连同确认语一起丢掉。
CATEGORIES = {"学业", "社交", "健康", "财务", "日常", "其他"}


def sanitize_memo(memo) -> dict:
    """把 Dify 产出的 memo 修成 Spring 一定接受的样子。"""
    memo = memo if isinstance(memo, dict) else {}
    title = str(memo.get("title") or "").strip()
    content = str(memo.get("content") or "").strip()
    due = str(memo.get("dueTime") or "").strip()
    try:
        importance = int(memo.get("importance"))
    except (TypeError, ValueError):
        importance = 3
    category = str(memo.get("category") or "").strip()
    out = {
        "title": title or (content[:40] or "备忘"),
        "importance": max(1, min(5, importance)),
        "category": category if category in CATEGORIES else "其他",
    }
    if content:
        out["content"] = content
    if due:
        out["dueTime"] = due
    return out


def parse_answer(answer: str) -> dict:
    """解析 Chatflow 的 Answer。失败/缺 action 一律 SILENT，绝不把原文发到 QQ。"""
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
    log.warning("dify answer is not a decision envelope: %r", (answer or "")[:200])
    return {"action": "SILENT"}


class Dispatcher:
    def __init__(self, spring, settings, reply):
        self._spring = spring
        self._setting = settings
        self._reply = reply

    async def handle(self, data: dict, ctx, sender_id: int, chat_id: int | None,
                     file_ctx: dict | None = None, conversation_key: str = "",
                     deps=None) -> str | None:
        action = data.get("action", "SILENT")

        if action == "SILENT":
            return None

        if action == "SEND":
            return await self._send(ctx, data.get("content", ""))

        if action == "WAIT_FILE":
            # 本地备份一份待填要求：Dify 会话被重置后仍能通过 pending_request_hint 带过去
            wait = data.get("wait") or {}
            if conversation_key:
                try:
                    await file_store.save_pending(
                        deps, conversation_key,
                        wait.get("request") or data.get("content", ""),
                        ttl_seconds=int(wait.get("ttl_seconds") or 900),
                        task_ref=(file_ctx or {}).get("taskRef", ""))
                except Exception as e:
                    log.warning("save pending hint failed: %s", e)
            return await self._send(ctx, data.get("content", ""))

        if action == "ASK":
            ask = data.get("ask") or {}
            ref = ask.get("task_ref") or (file_ctx or {}).get("taskRef")
            if ref:
                try:
                    await file_store.set_active_input(deps, sender_id, ref)
                except Exception as e:
                    log.warning("set active input failed: %s", e)
            return await self._send(ctx, data.get("content", ""))

        if action == "FILE_FILL":
            await self._file_fill(data, ctx, sender_id, file_ctx,
                                  conversation_key, deps)
            return None

        if action == "DRAFT":
            draft = data.get("draft") or {}
            await self._spring.create_draft(
                draft.get("type", "REPLY"), sender_id, chat_id,
                draft.get("title"), draft.get("payload") or {}
            )
            return None

        if action == "MEMO":
            memo = sanitize_memo(data.get("memo"))
            try:
                await self._spring.create_memo(
                    {**memo, "senderId": sender_id, "chatId": chat_id}
                )
            except Exception as e:
                # 存不进去就别回「记下了」——用户会以为已经记住
                log.warning("create memo failed: %s", e)
                return None
            if data.get("content"):
                return await self._send(ctx, data["content"])
            return None

        if action == "FACT":
            fact = data.get("fact") or {}
            try:
                confidence = float(fact.get("confidence", 0) or 0)
            except (TypeError, ValueError):
                confidence = 0.0
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

        if action == "MEMO_CLOSE":
            await self._close_memo(data.get("close") or {}, sender_id)
            if data.get("content"):
                return await self._send(ctx, data["content"])
            return None

        log.warning("unknown action %s, degrade to SILENT", action)
        return None

    async def _file_fill(self, data, ctx, sender_id, file_ctx,
                         conversation_key, deps):
        """FILE_FILL：定位本地任务 → 回写 → 回传/草稿。"""
        from core import file_task  # 延迟导入：文件依赖缺失时不影响文本消息

        payload = data.get("file") or {}
        ref = payload.get("task_ref") or ""
        task = None
        if ref:
            task = await file_store.load_task(deps, ref)
        if task is None and file_ctx and (
                not ref or file_ctx.get("taskRef") == ref):
            task = file_ctx
        if task is None:
            await self._send(ctx, "文件任务已过期，把文件重新发一次吧。")
            return

        try:
            await file_task.apply_fill(task, payload, deps, ctx)
            if conversation_key:
                await file_store.clear_pending(deps, conversation_key)
        except Exception as e:
            log.exception("apply file fill failed: %s", e)
            await self._send(ctx, f"文件回写失败：{e}")

    async def _close_memo(self, close: dict, sender_id: int):
        status = (close.get("status") or "DONE").upper()
        memo_id = close.get("memo_id")
        if not memo_id:
            for s in ("AWAITING_REPLY", "REMINDED"):
                items = await self._spring.list_memos(sender_id, s)
                if items:
                    memo_id = max(items, key=lambda m: m["id"])["id"]
                    break
        if memo_id:
            await self._spring.patch_memo(memo_id, {"status": status})

    async def _send(self, ctx, content: str) -> str | None:
        # LLM 输出常带首尾换行/空白，统一在此清洗，只剪首尾不动正文换行
        content = (content or "").strip()
        if not content:
            return None
        await asyncio.sleep(human_delay_seconds(content, self._setting))
        await self._reply(ctx, content)
        return content
