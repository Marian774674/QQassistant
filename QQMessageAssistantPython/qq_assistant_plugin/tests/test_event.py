"""inputs 契约：Dify 的 text-input 只吃字符串，字段缺失要退化而不是抛错。"""
from types import SimpleNamespace

from core.event import build_inputs, event_id_of


def test_defaults_are_dify_safe_strings():
    inputs = build_inputs(None, "text", "private", None, 1753800374)

    assert inputs["chat_id"] == ""          # 私聊不能传 null
    assert inputs["sender_id"] == "1753800374"
    assert inputs["facts"] == "{}"
    assert inputs["open_memos"] == "[]"     # 取不到就空列表，让模型反问而不是猜 id
    assert inputs["event_id"] == ""
    assert all(isinstance(v, str) for v in inputs.values())


def test_open_memos_is_json_not_repr():
    inputs = build_inputs(None, "text", "group", 123, 1,
                          open_memos=[{"id": 12, "title": "班会"}])
    assert inputs["open_memos"] == '[{"id": 12, "title": "班会"}]'


def test_event_id_prefers_platform_message_id():
    # 同一条消息被重投时值必须稳定，后端靠它去重
    assert event_id_of(SimpleNamespace(message_id="m-1")) == "m-1"
    assert event_id_of(SimpleNamespace(msg_id=12345)) == "12345"


def test_event_id_falls_back_to_uuid():
    first = event_id_of(SimpleNamespace())
    second = event_id_of(None)
    assert len(first) == 32 and len(second) == 32
    assert first != second
