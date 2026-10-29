import json
from unittest.mock import AsyncMock, patch

import pytest

from core import file_store
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


def test_parse_answer_bad_json_degrades_to_silent():
    """解析失败不能把原文发到 QQ（契约要求降级 SILENT）。"""
    assert parse_answer("随便说点什么") == {"action": "SILENT"}


def test_parse_answer_json_but_no_action_field():
    assert parse_answer('{"hello": 1}') == {"action": "SILENT"}


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
async def test_unknown_action_degrades_to_silent(spring, settings, reply):
    """不确定的动作宁可不回，也不能把脏数据发到 QQ。"""
    d = Dispatcher(spring, settings, reply)
    sent = await d.handle({"action": "WAT", "content": "脏文本"},
                          ctx="CTX", sender_id=1001, chat_id=None)

    assert sent is None
    reply.assert_not_awaited()


@pytest.mark.asyncio
async def test_wait_file_sends_and_records_pending_hint(spring, settings, reply,
                                                        tmp_path, monkeypatch):
    monkeypatch.setattr(file_store, "BASE", tmp_path / "files")
    d = Dispatcher(spring, settings, reply)
    sent = await d.handle(
        {"action": "WAIT_FILE", "content": "发过来吧",
         "wait": {"request": "把姓名填上", "ttl_seconds": 900}},
        ctx="CTX", sender_id=1001, chat_id=None,
        conversation_key="private|1001", deps=None)

    assert sent == "发过来吧"
    pending = await file_store.load_pending(None, "private|1001")
    assert pending["request"] == "把姓名填上"


@pytest.mark.asyncio
async def test_ask_marks_task_active(spring, settings, reply, tmp_path, monkeypatch):
    """ASK 之后的下一条文本必须能找回 structure。"""
    monkeypatch.setattr(file_store, "BASE", tmp_path / "files")
    d = Dispatcher(spring, settings, reply)
    sent = await d.handle(
        {"action": "ASK", "content": "要我填什么？",
         "ask": {"task_ref": "ft_1", "missing": []}},
        ctx="CTX", sender_id=1001, chat_id=None, deps=None)

    assert sent == "要我填什么？"
    assert await file_store.load_active_input(None, 1001) == "ft_1"


@pytest.mark.asyncio
async def test_file_fill_applies_task(spring, settings, reply, tmp_path, monkeypatch):
    monkeypatch.setattr(file_store, "BASE", tmp_path / "files")
    task = {"taskRef": "ft_1", "senderId": 1001}
    d = Dispatcher(spring, settings, reply)

    with patch("core.file_store.load_task", AsyncMock(return_value=task)), \
            patch("core.file_task.apply_fill", AsyncMock(return_value="SENT")) as apply:
        sent = await d.handle(
            {"action": "FILE_FILL", "file": {"task_ref": "ft_1", "fills": []}},
            ctx="CTX", sender_id=1001, chat_id=None,
            conversation_key="k", deps=None)

    assert sent is None
    apply.assert_awaited_once()


@pytest.mark.asyncio
async def test_memo_close_patches_latest_memo(spring, settings, reply):
    spring.list_memos.return_value = [{"id": 7, "title": "交报告"}]
    d = Dispatcher(spring, settings, reply)
    sent = await d.handle(
        {"action": "MEMO_CLOSE", "content": "已标记完成",
         "close": {"status": "DONE"}},
        ctx="CTX", sender_id=1001, chat_id=None)

    spring.patch_memo.assert_awaited_once_with(7, {"status": "DONE"})
    assert sent == "已标记完成"

# ---------- MEMO：字段兜底（模型写错枚举不能让整条备忘丢掉） ----------

def test_sanitize_memo_clamps_importance_and_fixes_category():
    from core.dispatch import sanitize_memo

    out = sanitize_memo({"title": "答辩", "importance": 9,
                         "category": "工作", "dueTime": "2026-11-01T09:00:00"})

    assert out == {"title": "答辩", "importance": 5, "category": "其他",
                   "dueTime": "2026-11-01T09:00:00"}


def test_sanitize_memo_defaults_missing_fields():
    from core.dispatch import sanitize_memo

    out = sanitize_memo({"content": "周五交报告"})

    assert out["title"] == "周五交报告"
    assert out["importance"] == 3
    assert out["category"] == "其他"
    assert "dueTime" not in out


@pytest.mark.asyncio
async def test_memo_creates_with_sanitized_payload(spring, settings, reply):
    d = Dispatcher(spring, settings, reply)

    sent = await d.handle(
        {"action": "MEMO", "content": "记下了",
         "memo": {"title": "班会", "importance": 3,
                  "category": "生活", "dueTime": "2026-11-01T19:00:00"}},
        ctx="CTX", sender_id=1001, chat_id=2002)

    spring.create_memo.assert_awaited_once_with({
        "title": "班会", "importance": 3, "category": "其他",
        "dueTime": "2026-11-01T19:00:00",
        "senderId": 1001, "chatId": 2002,
    })
    assert sent == "记下了"


@pytest.mark.asyncio
async def test_memo_save_failure_does_not_confirm(spring, settings, reply):
    """Spring 挂了还回「记下了」等于骗用户。"""
    spring.create_memo.side_effect = RuntimeError("spring down")
    d = Dispatcher(spring, settings, reply)

    sent = await d.handle(
        {"action": "MEMO", "content": "记下了", "memo": {"title": "班会"}},
        ctx="CTX", sender_id=1001, chat_id=None)

    assert sent is None
    reply.assert_not_awaited()
