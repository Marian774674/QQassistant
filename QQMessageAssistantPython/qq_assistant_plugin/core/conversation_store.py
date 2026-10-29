"""QQ 会话 ↔ Dify conversation_id 映射。

Dify 只有在请求带上 conversation_id 时才会延续上下文；没有它，每条消息
都是全新会话，用户上一轮说的“把姓名填上”自然就丢了。

持久化顺序：
1. 进程内缓存（最快，正常链路只走这里）
2. Spring `conversation_session`（跨重启、跨进程的真相源）
3. 本地 JSON（Spring 短暂不可用时的兜底，避免会话被意外打断）

会话键统一为 (bot_uuid, chat_type, chat_id, sender_id)：
- 私聊 chat_id 归一为 0，这样 MySQL 唯一键对私聊也生效；
- 群聊按成员隔离，避免 A 的待办污染 B 的文件任务。
"""
from __future__ import annotations

import json
import logging

from core import file_store

log = logging.getLogger(__name__)

_CACHE: dict[str, str] = {}

_MISSING_HINTS = (
    "conversation not exists",
    "conversation_not_exists",
    "conversation does not exist",
    "conversation not found",
)


def normalize_chat_id(chat_type: str, chat_id) -> int:
    if chat_type == "group":
        try:
            return int(chat_id)
        except (TypeError, ValueError):
            return 0
    return 0


def conversation_key(bot_uuid: str, chat_type: str, chat_id, sender_id) -> str:
    return "|".join((
        str(bot_uuid or "").strip(),
        chat_type,
        str(normalize_chat_id(chat_type, chat_id)),
        str(sender_id),
    ))


def dify_user(chat_type: str, chat_id, sender_id) -> str:
    """Dify `user` 字段；群聊按成员隔离，与会话键保持一致。"""
    if chat_type == "group" and normalize_chat_id(chat_type, chat_id):
        return f"group:{normalize_chat_id(chat_type, chat_id)}:{sender_id}"
    return f"private:{sender_id}"


def is_missing_conversation(error: Exception) -> bool:
    """Dify 在 conversation_id 失效时返回 400 Conversation Not Exists。"""
    text = str(error).lower()
    return any(hint in text for hint in _MISSING_HINTS)


def clear_cache() -> None:
    _CACHE.clear()


def _local_path():
    return file_store.BASE / "_conversations.json"


def _load_local() -> dict:
    try:
        raw = _local_path().read_text(encoding="utf-8")
        data = json.loads(raw) if raw else {}
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def _save_local(data: dict) -> None:
    try:
        path = _local_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name(path.name + ".tmp")
        tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(path)
    except Exception as e:
        log.debug("save conversation map failed: %r", e)


def _remember(key: str, conversation_id: str) -> None:
    _CACHE[key] = conversation_id
    data = _load_local()
    if data.get(key) != conversation_id:
        data[key] = conversation_id
        _save_local(data)


def _forget(key: str) -> None:
    _CACHE.pop(key, None)
    data = _load_local()
    if key in data:
        data.pop(key)
        _save_local(data)


async def resolve(deps, bot_uuid: str, chat_type: str, chat_id, sender_id) -> str | None:
    """取当前会话的 conversation_id；没有则返回 None（Dify 会新建）。"""
    key = conversation_key(bot_uuid, chat_type, chat_id, sender_id)
    cached = _CACHE.get(key)
    if cached:
        return cached

    if bot_uuid and getattr(deps, "spring", None):
        try:
            remote = await deps.spring.resolve_conversation(
                bot_uuid, chat_type, normalize_chat_id(chat_type, chat_id), sender_id)
        except Exception as e:
            log.warning("resolve conversation failed, use local fallback: %s", e)
            file_store.debug(f"CONV resolve spring failed: {e!r}")
            return _load_local().get(key) or None
        if remote:
            _remember(key, remote)
            return remote
        # Spring 是真相源：它说没有（含 RESET），就不能让本地旧值复活
        _forget(key)
        return None

    return _load_local().get(key) or None


async def bind(deps, bot_uuid: str, chat_type: str, chat_id, sender_id,
               conversation_id: str) -> None:
    """缓存 Dify 返回的新 conversation_id，并持久化到 Spring。"""
    if not conversation_id:
        return
    key = conversation_key(bot_uuid, chat_type, chat_id, sender_id)
    _remember(key, conversation_id)
    if not (bot_uuid and getattr(deps, "spring", None)):
        return
    try:
        await deps.spring.bind_conversation(
            bot_uuid, chat_type, normalize_chat_id(chat_type, chat_id),
            sender_id, conversation_id)
    except Exception as e:
        # 本地已记住，Spring 恢复后仍可用；不能让持久化失败打断回复
        log.warning("bind conversation failed: %s", e)
        file_store.debug(f"CONV bind spring failed: {e!r}")


async def reset(deps, bot_uuid: str, chat_type: str, chat_id, sender_id) -> None:
    """Dify 会话失效/用户要求重开时调用：本地和 Spring 一起清掉。"""
    key = conversation_key(bot_uuid, chat_type, chat_id, sender_id)
    _forget(key)
    if not (bot_uuid and getattr(deps, "spring", None)):
        return
    try:
        await deps.spring.reset_conversation(
            bot_uuid, chat_type, normalize_chat_id(chat_type, chat_id), sender_id)
    except Exception as e:
        log.warning("reset conversation failed: %s", e)
        file_store.debug(f"CONV reset spring failed: {e!r}")
