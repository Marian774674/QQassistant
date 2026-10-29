"""会话映射回归：同一 QQ 会话必须复用同一个 Dify conversation_id。"""
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from core import conversation_store, file_store


@pytest.fixture(autouse=True)
def isolated(tmp_path, monkeypatch):
    monkeypatch.setattr(file_store, "BASE", tmp_path / "files")
    conversation_store.clear_cache()
    yield
    conversation_store.clear_cache()


def fake_deps(resolve=None, bind_error=None, reset_error=None):
    spring = SimpleNamespace(
        resolve_conversation=AsyncMock(return_value=resolve),
        bind_conversation=AsyncMock(side_effect=bind_error),
        reset_conversation=AsyncMock(side_effect=reset_error),
    )
    return SimpleNamespace(spring=spring)


@pytest.mark.asyncio
async def test_resolve_uses_spring_once_then_cache():
    deps = fake_deps(resolve="conv-1")

    first = await conversation_store.resolve(deps, "bot", "private", None, 1001)
    second = await conversation_store.resolve(deps, "bot", "private", None, 1001)

    assert first == second == "conv-1"
    deps.spring.resolve_conversation.assert_awaited_once_with(
        "bot", "private", 0, 1001)


@pytest.mark.asyncio
async def test_bind_persists_and_survives_spring_outage():
    deps = fake_deps()
    await conversation_store.bind(deps, "bot", "private", None, 1001, "conv-1")
    deps.spring.bind_conversation.assert_awaited_once_with(
        "bot", "private", 0, 1001, "conv-1")

    # 模拟补丁：Spring 挂了，本地 JSON 仍然要能续上会话
    deps.spring.resolve_conversation.side_effect = RuntimeError("spring down")
    conversation_store.clear_cache()
    assert await conversation_store.resolve(
        deps, "bot", "private", None, 1001) == "conv-1"


@pytest.mark.asyncio
async def test_spring_none_does_not_resurrect_local_stale_value():
    deps = fake_deps()
    await conversation_store.bind(deps, "bot", "private", None, 1001, "conv-1")
    conversation_store.clear_cache()

    assert await conversation_store.resolve(
        deps, "bot", "private", None, 1001) is None
    # 本地旧值必须一起清掉，否则 RESET 会被绕过
    assert await conversation_store.resolve(
        deps, "bot", "private", None, 1001) is None


@pytest.mark.asyncio
async def test_group_and_private_are_isolated():
    deps = fake_deps()
    await conversation_store.bind(deps, "bot", "group", 555, 1001, "group-conv")
    await conversation_store.bind(deps, "bot", "group", 555, 1002, "other-conv")

    assert await conversation_store.resolve(deps, "bot", "group", 555, 1001) == "group-conv"
    assert await conversation_store.resolve(deps, "bot", "group", 555, 1002) == "other-conv"
    assert await conversation_store.resolve(deps, "bot", "private", None, 1001) is None
    # 群聊 chat_id 必须原样上报，不能被归一成 0
    deps.spring.bind_conversation.assert_any_await("bot", "group", 555, 1001, "group-conv")


@pytest.mark.asyncio
async def test_reset_clears_cache_and_spring():
    deps = fake_deps(resolve="conv-1")
    await conversation_store.resolve(deps, "bot", "private", None, 1001)
    key = conversation_store.conversation_key("bot", "private", None, 1001)
    assert key in conversation_store._CACHE

    await conversation_store.reset(deps, "bot", "private", None, 1001)

    deps.spring.reset_conversation.assert_awaited_once_with(
        "bot", "private", 0, 1001)
    assert key not in conversation_store._CACHE
    assert key not in conversation_store._load_local()
    deps.spring.resolve_conversation.return_value = None
    assert await conversation_store.resolve(
        deps, "bot", "private", None, 1001) is None


@pytest.mark.asyncio
async def test_bind_failure_keeps_local_mapping():
    deps = fake_deps(bind_error=RuntimeError("spring down"))
    await conversation_store.bind(deps, "bot", "private", None, 1001, "conv-1")

    # 缓存兜底，用户不会因为持久化失败被换会话
    assert conversation_store._CACHE[
        conversation_store.conversation_key("bot", "private", None, 1001)
    ] == "conv-1"


def test_dify_user_formats_private_and_group():
    assert conversation_store.dify_user("private", None, 1001) == "private:1001"
    assert conversation_store.dify_user("group", 555, 1001) == "group:555:1001"


def test_is_missing_conversation_detects_dify_error():
    assert conversation_store.is_missing_conversation(
        RuntimeError('Dify /chat-messages HTTP 400: {"message":"Conversation Not Exists."}'))
    assert not conversation_store.is_missing_conversation(RuntimeError("HTTP 500"))
