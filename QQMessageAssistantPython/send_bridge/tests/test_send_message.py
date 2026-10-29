import json

import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from send_bridge.app import create_app
from send_bridge.config import Settings


def make_settings(tmp_path, **overrides):
    base = dict(
        langbot_base="http://lb",
        langbot_bot_uuid="u1",
        langbot_api_key="k",
        spring_base="http://sb",
        napcat_base="http://nc",
        napcat_token="t",
        # 延迟归零：测试不睡觉，结果可预测
        delay_base_ms=0,
        delay_per_char_ms=0,
        delay_jitter_ms=0,
        delay_max_ms=0,
        state_file=str(tmp_path / "sent.json"),
    )
    base.update(overrides)
    return Settings(**base)


@respx.mock
def test_maps_private_to_person_and_logs_out(tmp_path):
    sent = respx.post("http://lb/api/v1/platform/bots/u1/send_message").mock(
        return_value=httpx.Response(200, json={"status": "success", "message_id": "m1"}))
    logs = respx.post("http://sb/api/message-logs").mock(
        return_value=httpx.Response(200, json={"code": 0, "message": "ok", "data": 1}))

    client = TestClient(create_app(make_settings(tmp_path)))
    r = client.post("/api/langbot/send_message", json={
        "target": {"type": "private", "id": 1001}, "content": "在吗", "bizRef": "memo:1"})

    assert r.status_code == 200
    assert r.json() == {"code": 0, "message": "ok", "data": {"messageId": "m1"}}
    body = json.loads(sent.calls[0].request.content)
    assert body["target_type"] == "person"
    assert body["target_id"] == "1001"
    assert body["message_chain"] == [{"type": "Plain", "text": "在吗"}]
    assert sent.calls[0].request.headers["X-API-Key"] == "k"

    logged = json.loads(logs.calls[0].request.content)[0]
    assert logged["direction"] == "OUT"
    assert logged["msgType"] == "text"
    assert logged["senderId"] == 1001
    assert logged["chatId"] is None


@respx.mock
def test_group_target_keeps_group_type_and_chat_id(tmp_path):
    sent = respx.post("http://lb/api/v1/platform/bots/u1/send_message").mock(
        return_value=httpx.Response(200, json={"status": "success", "message_id": "m2"}))
    logs = respx.post("http://sb/api/message-logs").mock(
        return_value=httpx.Response(200, json={"code": 0, "message": "ok", "data": 1}))

    client = TestClient(create_app(make_settings(tmp_path)))
    client.post("/api/langbot/send_message", json={
        "target": {"type": "group", "id": 2002}, "content": "开会了"})

    body = json.loads(sent.calls[0].request.content)
    assert body["target_type"] == "group"
    logged = json.loads(logs.calls[0].request.content)[0]
    assert logged["chatId"] == 2002


def test_blank_content_returns_400(tmp_path):
    client = TestClient(create_app(make_settings(tmp_path)))
    r = client.post("/api/langbot/send_message", json={
        "target": {"type": "private", "id": 1001}, "content": "   "})
    assert r.status_code == 400
    assert r.json()["code"] == 400


def test_bad_target_type_returns_400(tmp_path):
    client = TestClient(create_app(make_settings(tmp_path)))
    r = client.post("/api/langbot/send_message", json={
        "target": {"type": "channel", "id": 1}, "content": "x"})
    assert r.status_code == 400
    assert r.json()["code"] == 400


@respx.mock
def test_upstream_error_returns_500_envelope(tmp_path):
    respx.post("http://lb/api/v1/platform/bots/u1/send_message").mock(
        return_value=httpx.Response(500, text="boom"))

    client = TestClient(create_app(make_settings(tmp_path)))
    r = client.post("/api/langbot/send_message", json={
        "target": {"type": "private", "id": 1001}, "content": "x"})

    assert r.status_code == 500
    assert r.json()["code"] == 500
    assert "发送失败" in r.json()["message"]


@respx.mock
def test_same_biz_ref_sends_only_once(tmp_path):
    sent = respx.post("http://lb/api/v1/platform/bots/u1/send_message").mock(
        return_value=httpx.Response(200, json={"status": "success", "message_id": "m3"}))
    respx.post("http://sb/api/message-logs").mock(
        return_value=httpx.Response(200, json={"code": 0, "message": "ok", "data": 1}))

    client = TestClient(create_app(make_settings(tmp_path)))
    payload = {"target": {"type": "private", "id": 1001}, "content": "确认", "bizRef": "draft:9"}
    first = client.post("/api/langbot/send_message", json=payload)
    second = client.post("/api/langbot/send_message", json=payload)

    assert sent.call_count == 1
    assert first.json()["data"]["messageId"] == "m3"
    assert second.json()["data"]["messageId"] == "m3"


@respx.mock
def test_different_biz_ref_sends_each_time(tmp_path):
    sent = respx.post("http://lb/api/v1/platform/bots/u1/send_message").mock(
        return_value=httpx.Response(200, json={"status": "success", "message_id": "m4"}))
    respx.post("http://sb/api/message-logs").mock(
        return_value=httpx.Response(200, json={"code": 0, "message": "ok", "data": 1}))

    client = TestClient(create_app(make_settings(tmp_path)))
    client.post("/api/langbot/send_message", json={
        "target": {"type": "private", "id": 1001}, "content": "a", "bizRef": "draft:1"})
    client.post("/api/langbot/send_message", json={
        "target": {"type": "private", "id": 1001}, "content": "b", "bizRef": "draft:2"})

    assert sent.call_count == 2


@respx.mock
def test_out_log_failure_does_not_fail_the_send(tmp_path):
    respx.post("http://lb/api/v1/platform/bots/u1/send_message").mock(
        return_value=httpx.Response(200, json={"status": "success", "message_id": "m5"}))
    respx.post("http://sb/api/message-logs").mock(return_value=httpx.Response(500, text="db down"))

    client = TestClient(create_app(make_settings(tmp_path)))
    r = client.post("/api/langbot/send_message", json={
        "target": {"type": "private", "id": 1001}, "content": "x"})

    assert r.status_code == 200
    assert r.json()["data"]["messageId"] == "m5"


# --- C2: 并发同一 bizRef 不得发送两次 ---

@pytest.mark.asyncio
@respx.mock
async def test_concurrent_same_bizref_sends_once(tmp_path):
    import asyncio

    from send_bridge.upstream import LangBotUpstream

    started = asyncio.Event()
    release = asyncio.Event()
    calls = []

    async def fake_send(self, target_type, target_id, text):
        calls.append(text)
        started.set()
        await release.wait()
        return "m1"

    async def fake_close(self):
        return None

    monkeypatch = pytest.MonkeyPatch()
    monkeypatch.setattr(LangBotUpstream, "send_text", fake_send)
    monkeypatch.setattr(LangBotUpstream, "close", fake_close)
    respx.post("http://sb/api/message-logs").mock(
        return_value=httpx.Response(200, json={"code": 0, "message": "ok", "data": 1}))

    try:
        app = create_app(make_settings(tmp_path))
        transport = httpx.ASGITransport(app=app)
        payload = {"target": {"type": "private", "id": 1001},
                   "content": "提醒", "bizRef": "memo:1:1"}
        async with httpx.AsyncClient(transport=transport, base_url="http://t") as c:
            first = asyncio.create_task(c.post("/api/langbot/send_message", json=payload))
            await started.wait()
            try:
                second = await asyncio.wait_for(
                    c.post("/api/langbot/send_message", json=payload), timeout=3)
            finally:
                release.set()
                r1 = await first
    finally:
        monkeypatch.undo()

    assert second.status_code == 409
    assert len(calls) == 1
    assert r1.status_code == 200
    assert r1.json()["data"]["messageId"] == "m1"


@respx.mock
def test_out_log_carries_dedupe_key(tmp_path):
    respx.post("http://lb/api/v1/platform/bots/u1/send_message").mock(
        return_value=httpx.Response(200, json={"status": "success", "message_id": "m7"}))
    logs = respx.post("http://sb/api/message-logs").mock(
        return_value=httpx.Response(200, json={"code": 0, "message": "ok", "data": 1}))

    client = TestClient(create_app(make_settings(tmp_path)))
    client.post("/api/langbot/send_message", json={
        "target": {"type": "private", "id": 1001}, "content": "在吗"})

    logged = json.loads(logs.calls[0].request.content)[0]
    assert logged["dedupeKey"] == "out:m7"
