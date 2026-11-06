"""把 Dify 回传的 token 用量记到 Spring 的只读账本。

设计要点：**旁路**。记账不能拖慢、更不能打断消息主流程——Spring 没开、
表没迁移、网络抖动，都只是丢一笔统计，QQ 回复必须照发。所以用
`create_task` 发出去就不管，协程内部自己吞掉所有异常。
"""
from __future__ import annotations

import asyncio
import logging

log = logging.getLogger(__name__)

# 保留强引用：CPython 的事件循环只持有 task 的弱引用，任务可能被提前回收
_PENDING: set[asyncio.Task] = set()


def _as_int(value) -> int:
    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return 0


def usage_of(result: dict) -> dict | None:
    """从 Dify 响应里抠出用量，统一成 Spring DTO 的字段名。

    chat-messages 给 ``metadata.usage``（有 prompt/completion 拆分）；
    workflows/run 只给 ``data.total_tokens``。两种都要能记。
    没有用量字段时返回 None——别往账本里写一行 0。
    """
    if not isinstance(result, dict):
        return None
    metadata = result.get("metadata") or {}
    data = result.get("data") or {}
    usage = (metadata.get("usage") if isinstance(metadata, dict) else None) or {}
    if not isinstance(usage, dict):
        usage = {}

    prompt = _as_int(usage.get("prompt_tokens"))
    completion = _as_int(usage.get("completion_tokens"))
    total = _as_int(usage.get("total_tokens"))
    if not total and isinstance(data, dict) and data.get("total_tokens") is not None:
        total = _as_int(data.get("total_tokens"))
    if not (prompt or completion or total):
        return None
    if not total:
        total = prompt + completion
    return {
        "promptTokens": prompt,
        "completionTokens": completion,
        "totalTokens": total,
    }


def payload_of(result: dict, task: str, chat_id=None, model: str = "") -> dict | None:
    usage = usage_of(result)
    if not usage:
        return None
    payload = {
        "source": "plugin",
        "task": task,
        "promptTokens": usage["promptTokens"],
        "completionTokens": usage["completionTokens"],
        "totalTokens": usage["totalTokens"],
    }
    if model:
        payload["model"] = model
    if chat_id:
        payload["chatId"] = chat_id
    return payload


async def _post(spring, payload: dict) -> None:
    try:
        await spring.record_token_usage(payload)
    except Exception as e:  # noqa: BLE001 - 账本是旁路边，任何失败都只记 debug
        log.debug("token usage 上报失败（不影响消息处理）：%r", e)


def enqueue(spring, result: dict, task: str = "chat", chat_id=None, model: str = "") -> bool:
    """发一笔用量记录，不等结果。返回是否真的排了任务。"""
    payload = payload_of(result, task, chat_id, model)
    if not payload or spring is None:
        return False
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return False
    pending = loop.create_task(_post(spring, payload))
    _PENDING.add(pending)
    pending.add_done_callback(_PENDING.discard)
    return True
