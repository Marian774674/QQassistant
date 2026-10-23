import json
from unittest.mock import AsyncMock

import pytest

from core.config import Settings
from core.dispatch import Dispatcher, parse_answer


@pytest.fixture
def settings():
    """延迟全设 0：SEND 分支测试不想真的 sleep，jitter=0 让结果可预测。"""
    return Settings(
        spring_base_url="http://sb",
        spring_timeout=5,
        dify_base_url="http://dify/v1",
        dify_api_key="k",
        dify_timeout=60,
        dify_file_workflow_key="wf-key",
        file_confirm_threshold=0.85,
        bot_uuid="u",
        bot_qq=12345,
        group_prefix="!",
        fact_auto_threshold=0.85,
        delay_base_ms=0,
        delay_per_char_ms=0,
        delay_jitter_ms=0,
        delay_max_ms=0,
        intercept_refresh_s=60,
        intercept_scope="group",
    )


@pytest.fixture
def spring():
    """所有方法都是 AsyncMock：await 得通，还能断言调用记录。"""
    m = AsyncMock()
    m.create_draft = AsyncMock(return_value={"id": 1})
    m.create_memo = AsyncMock(return_value={"id": 2})
    m.upsert_fact = AsyncMock(return_value={"id": 3})
    return m


@pytest.fixture
def reply():
    return AsyncMock()


# ---------- parse_answer：纯同步，不需要 fixture ----------

def test_parse_answer_plain_json():
    answer = json.dumps({"action": "SILENT", "confidence": 0.9}, ensure_ascii=False)
    assert parse_answer(answer)["action"] == "SILENT"


def test_parse_answer_markdown_fenced_json():
    inner = {"action": "SEND", "content": "你好"}
    assert parse_answer(f"```json\n{json.dumps(inner, ensure_ascii=False)}\n```") == inner


def test_parse_answer_bad_json_falls_back_to_send():
    assert parse_answer("随便说点什么") == {"action": "SEND", "content": "随便说点什么"}


def test_parse_answer_json_but_no_action_field():
    # 合法 JSON 但没有 action —— 同样必须兜底，不能往下走分发
    assert parse_answer('{"hello": 1}')[0 if False else "action"] == "SEND"


# ---------- Dispatcher.handle：异步分发 ----------

@pytest.mark.asyncio
async def test_fact_low_confidence_creates_draft_not_upsert(spring, settings, reply):
    d = Dispatcher(spring, settings, reply)
    fact = {"factKey": "专业", "factValue": "计算机", "confidence": 0.5}
    await d.handle({"action": "FACT", "fact": fact}, ctx=None,
                   sender_id=1001, chat_id=None)

    spring.create_draft.assert_awaited_once_with(
        "FACT", 1001, None, "待确认事实", fact)
    spring.upsert_fact.assert_not_awaited()


@pytest.mark.asyncio
async def test_fact_high_confidence_upserts(spring, settings, reply):
    d = Dispatcher(spring, settings, reply)
    await d.handle({"action": "FACT",
                    "fact": {"factKey": "专业", "factValue": "计算机", "confidence": 0.95}},
                   ctx=None, sender_id=1001, chat_id=None)

    spring.upsert_fact.assert_awaited_once_with(1001, "专业", "计算机", 0.95)
    spring.create_draft.assert_not_awaited()


@pytest.mark.asyncio
async def test_send_replies_and_returns_text(spring, settings, reply):
    d = Dispatcher(spring, settings, reply)
    sent = await d.handle({"action": "SEND", "content": "在的"},
                          ctx="CTX", sender_id=1001, chat_id=None)

    assert sent == "在的"
    reply.assert_awaited_once_with("CTX", "在的")


@pytest.mark.asyncio
async def test_unknown_action_falls_back_to_send(spring, settings, reply):
    d = Dispatcher(spring, settings, reply)
    sent = await d.handle({"action": "WAT", "content": "兜底文本"},
                          ctx="CTX", sender_id=1001, chat_id=None)

    assert sent == "兜底文本"
    reply.assert_awaited_once()