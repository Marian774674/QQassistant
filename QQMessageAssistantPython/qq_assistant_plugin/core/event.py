"""Dify 事件输入构造：把插件侧的上下文压成 Start 节点的 14 个变量。

约定见 design/agent_contract.md。这里只做机械拼装，不做任何语义判断：
是否要填、缺什么、要不要问，全部由 Dify Chatflow 决定。
"""
import datetime
import json
from pathlib import Path

_TEXT = {"xlsx", "docx", "pdf"}


def now_iso() -> str:
    """带时区偏移的 ISO 时间；Chatflow 的 CLOCK 节点靠它算过期时间。"""
    return datetime.datetime.now().astimezone().isoformat(timespec="seconds")


def file_kind(name: str) -> str:
    """xlsx / docx / pdf / other。"""
    ext = Path(name or "").suffix.lower().lstrip(".")
    return ext if ext in _TEXT else "other"


def build_inputs(profile: dict | None, event_type: str, chat_type: str,
                 chat_id: int | None, sender_id: int,
                 file_task: dict | None = None,
                 pending_request_hint: str = "",
                 last_task_ref: str = "") -> dict:
    """生成 /chat-messages 的 inputs。

    Dify 的 text-input 只接受字符串：facts/structure 必须 json.dumps，
    chat_id 私聊传空串（不能传 null）。
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
    }
