"""Dify 事件输入构造：把插件侧的上下文压成 Start 节点的变量。

约定见 design/agent_contract.md。这里只做机械拼装，不做任何语义判断：
是否要填、缺什么、要不要问，全部由 Dify Chatflow 决定。
"""
import datetime
import json
import uuid
from pathlib import Path

_TEXT = {"xlsx", "docx", "pdf"}

# 平台消息 id 在不同 LangBot 版本/适配器上的字段名不一致，按可能性依次取
_EVENT_ID_ATTRS = ("message_id", "msg_id", "id")


def now_iso() -> str:
    """带时区偏移的 ISO 时间；Chatflow 的 CLOCK 节点靠它算过期时间。"""
    return datetime.datetime.now().astimezone().isoformat(timespec="seconds")


def event_id_of(event) -> str:
    """写操作的幂等键。

    优先用平台消息 id（同一条消息被重投时值不变，后端就能把重复执行挡掉）；
    拿不到就退化成 uuid4——此时幂等只保「同一次调用内的重试」，
    所以必须在调 Dify 之前生成一次、重试时复用，不能在重试里重新生成。
    """
    for attr in _EVENT_ID_ATTRS:
        value = getattr(event, attr, None)
        if value:
            return str(value)[:64]
    return uuid.uuid4().hex


def file_kind(name: str) -> str:
    """xlsx / docx / pdf / other。"""
    ext = Path(name or "").suffix.lower().lstrip(".")
    return ext if ext in _TEXT else "other"


def build_inputs(profile: dict | None, event_type: str, chat_type: str,
                 chat_id: int | None, sender_id: int,
                 file_task: dict | None = None,
                 pending_request_hint: str = "",
                 last_task_ref: str = "",
                 open_memos: list | None = None,
                 event_id: str = "") -> dict:
    """生成 /chat-messages 的 inputs。

    Dify 的 text-input 只接受字符串：facts/structure/open_memos 必须 json.dumps，
    chat_id 私聊传空串（不能传 null）。

    open_memos 是「用户还挂着哪些备忘」的快照：Dify 只能从这里取 memoId 去改/取消，
    取不到就给 []——宁可让模型反问一句，也不能让它猜一个 id。
    """
    profile = profile or {}
    task = file_task or {}
    structure = task.get("structure")
    return {
        "event_type": event_type,
        "facts": json.dumps(profile.get("facts") or {}, ensure_ascii=False),
        "style_prompt": profile.get("stylePrompt") or "",
        "chat_type": chat_type,
        "chat_id": "" if chat_id is None else str(chat_id),
        "sender_id": str(sender_id),
        "current_time": now_iso(),
        "file_task_ref": task.get("taskRef") or "",
        "file_name": task.get("originalName") or "",
        "file_kind": file_kind(task.get("originalName") or ""),
        "structure": json.dumps(structure, ensure_ascii=False) if structure else "",
        "pending_request_hint": pending_request_hint or "",
        "last_task_ref": last_task_ref or "",
        "open_memos": json.dumps(open_memos or [], ensure_ascii=False),
        "event_id": event_id or "",
    }
