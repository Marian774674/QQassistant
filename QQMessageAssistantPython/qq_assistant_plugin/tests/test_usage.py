"""Token 用量账本的插件侧：解析 Dify 响应 + 旁路 fire-and-forget 上报。

钉住两件事：
1. chat-messages 的 ``metadata.usage`` 和 workflows 的 ``data.total_tokens``
   两种口径都要能记；没有用量字段绝不能往账本里写一行 0。
2. 记账失败（Spring 挂了 / 表没迁移 / 没有事件循环）只能丢统计，
   不能把异常抛回消息主流程。
"""
import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from core import usage


def test_chat_messages_usage_has_prompt_completion_split():
    result = {
        "answer": '{"action":"SILENT"}',
        "metadata": {
            "usage": {
                "prompt_tokens": 120,
                "completion_tokens": 30,
                "total_tokens": 150,
            }
        },
    }
    assert usage.usage_of(result) == {
        "promptTokens": 120,
        "completionTokens": 30,
        "totalTokens": 150,
    }


def test_workflow_only_gives_total_tokens():
    result = {"data": {"outputs": {}, "total_tokens": 88}}
    assert usage.usage_of(result) == {
        "promptTokens": 0,
        "completionTokens": 0,
        "totalTokens": 88,
    }


def test_missing_total_is_derived_from_split():
    result = {"metadata": {"usage": {"prompt_tokens": "12", "completion_tokens": "8"}}}
    assert usage.usage_of(result)["totalTokens"] == 20


def test_no_usage_returns_none():
    assert usage.usage_of({"answer": "hi"}) is None
    assert usage.usage_of({"metadata": {}}) is None
    assert usage.usage_of(None) is None
    assert usage.usage_of({"metadata": "not a dict"}) is None
    assert usage.usage_of({"data": {"total_tokens": "not-a-number"}}) is None


def test_payload_carries_source_task_and_optional_labels():
    result = {"metadata": {"usage": {"total_tokens": 42}}}
    payload = usage.payload_of(result, "chat", chat_id=777, model="qwen-plus")
    assert payload == {
        "source": "plugin",
        "task": "chat",
        "promptTokens": 0,
        "completionTokens": 0,
        "totalTokens": 42,
        "model": "qwen-plus",
        "chatId": 777,
    }
    # 没标模型 / 私聊没有群号时不要塞空字段，前端「未标注」靠的是 null
    bare = usage.payload_of(result, "chat")
    assert "model" not in bare and "chatId" not in bare
    assert usage.payload_of({"answer": "hi"}, "chat") is None


@pytest.mark.asyncio
async def test_enqueue_posts_without_waiting():
    spring = SimpleNamespace(record_token_usage=AsyncMock(return_value=1))
    result = {"metadata": {"usage": {"total_tokens": 15}}}

    assert usage.enqueue(spring, result, task="chat", chat_id=555) is True
    # fire-and-forget：让事件循环把刚排进去的 task 跑完
    for _ in range(3):
        await asyncio.sleep(0)

    spring.record_token_usage.assert_awaited_once()
    body = spring.record_token_usage.await_args.args[0]
    assert body["totalTokens"] == 15
    assert body["chatId"] == 555


@pytest.mark.asyncio
async def test_enqueue_swallows_spring_failure():
    spring = SimpleNamespace(
        record_token_usage=AsyncMock(side_effect=RuntimeError("spring down")))

    assert usage.enqueue(spring, {"data": {"total_tokens": 3}}) is True
    for _ in range(3):
        await asyncio.sleep(0)
    # 没有异常冒到这里就是合格；主流程该发的话照发


def test_enqueue_without_loop_or_spring_is_a_noop():
    result = {"data": {"total_tokens": 3}}
    assert usage.enqueue(None, result) is False
    assert usage.enqueue(object(), result) is False  # 没有运行中的事件循环
    assert usage.enqueue(object(), {"answer": "hi"}) is False
