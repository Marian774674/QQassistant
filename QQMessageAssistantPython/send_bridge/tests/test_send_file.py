import json

import httpx
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
        delay_base_ms=0,
        delay_per_char_ms=0,
        delay_jitter_ms=0,
        delay_max_ms=0,
        state_file=str(tmp_path / "sent.json"),
        file_via_langbot=False,
    )
    base.update(overrides)
    return Settings(**base)


def make_file(tmp_path):
    path = tmp_path / "表单_filled.xlsx"
    path.write_bytes(b"fake-xlsx")
    return path


@respx.mock
def test_private_file_goes_through_napcat(tmp_path):
    path = make_file(tmp_path)
    napcat = respx.post("http://nc/upload_private_file").mock(
        return_value=httpx.Response(200, json={"status": "ok", "data": {"message_id": "f1"}}))
    respx.post("http://sb/api/message-logs").mock(
        return_value=httpx.Response(200, json={"code": 0, "message": "ok", "data": 1}))

    client = TestClient(create_app(make_settings(tmp_path)))
    r = client.post("/api/langbot/send_file", json={
        "target": {"type": "private", "id": 1001},
        "fileRef": f"local://{path}", "fileName": path.name, "bizRef": "draft:7"})

    assert r.status_code == 200
    assert r.json()["data"]["messageId"] == "f1"
    body = json.loads(napcat.calls[0].request.content)
    assert body["user_id"] == 1001
    assert body["file"] == str(path)
    assert body["name"] == path.name
    assert napcat.calls[0].request.headers["Authorization"] == "Bearer t"


@respx.mock
def test_group_file_goes_through_napcat_group_upload(tmp_path):
    path = make_file(tmp_path)
    napcat = respx.post("http://nc/upload_group_file").mock(
        return_value=httpx.Response(200, json={"status": "ok", "data": {"message_id": "f2"}}))
    respx.post("http://sb/api/message-logs").mock(
        return_value=httpx.Response(200, json={"code": 0, "message": "ok", "data": 1}))

    client = TestClient(create_app(make_settings(tmp_path)))
    client.post("/api/langbot/send_file", json={
        "target": {"type": "group", "id": 2002}, "fileRef": f"local://{path}"})

    body = json.loads(napcat.calls[0].request.content)
    assert body["group_id"] == 2002
    assert body["name"] == path.name


def test_missing_file_returns_400(tmp_path):
    client = TestClient(create_app(make_settings(tmp_path)))
    r = client.post("/api/langbot/send_file", json={
        "target": {"type": "private", "id": 1001},
        "fileRef": f"local://{tmp_path / 'nope.xlsx'}"})
    assert r.status_code == 400
    assert "不存在" in r.json()["message"]


def test_blank_file_ref_returns_400(tmp_path):
    client = TestClient(create_app(make_settings(tmp_path)))
    r = client.post("/api/langbot/send_file", json={
        "target": {"type": "private", "id": 1001}, "fileRef": "  "})
    assert r.status_code == 400


@respx.mock
def test_langbot_file_channel_used_when_enabled(tmp_path):
    path = make_file(tmp_path)
    lb = respx.post("http://lb/api/v1/platform/bots/u1/send_message").mock(
        return_value=httpx.Response(200, json={"status": "success", "message_id": "lf1"}))
    respx.post("http://sb/api/message-logs").mock(
        return_value=httpx.Response(200, json={"code": 0, "message": "ok", "data": 1}))

    client = TestClient(create_app(make_settings(tmp_path, file_via_langbot=True)))
    r = client.post("/api/langbot/send_file", json={
        "target": {"type": "private", "id": 1001}, "fileRef": f"local://{path}"})

    assert r.json()["data"]["messageId"] == "lf1"
    body = json.loads(lb.calls[0].request.content)
    assert body["message_chain"][0]["type"] == "File"


@respx.mock
def test_langbot_file_failure_falls_back_to_napcat(tmp_path):
    path = make_file(tmp_path)
    respx.post("http://lb/api/v1/platform/bots/u1/send_message").mock(
        return_value=httpx.Response(400, text="unsupported component"))
    napcat = respx.post("http://nc/upload_private_file").mock(
        return_value=httpx.Response(200, json={"status": "ok", "data": {"message_id": "f3"}}))
    respx.post("http://sb/api/message-logs").mock(
        return_value=httpx.Response(200, json={"code": 0, "message": "ok", "data": 1}))

    client = TestClient(create_app(make_settings(tmp_path, file_via_langbot=True)))
    r = client.post("/api/langbot/send_file", json={
        "target": {"type": "private", "id": 1001}, "fileRef": f"local://{path}"})

    assert r.status_code == 200
    assert r.json()["data"]["messageId"] == "f3"
    assert napcat.call_count == 1


@respx.mock
def test_file_biz_ref_is_idempotent(tmp_path):
    path = make_file(tmp_path)
    napcat = respx.post("http://nc/upload_private_file").mock(
        return_value=httpx.Response(200, json={"status": "ok", "data": {"message_id": "f4"}}))
    respx.post("http://sb/api/message-logs").mock(
        return_value=httpx.Response(200, json={"code": 0, "message": "ok", "data": 1}))

    client = TestClient(create_app(make_settings(tmp_path)))
    payload = {"target": {"type": "private", "id": 1001},
               "fileRef": f"local://{path}", "bizRef": "draft:8"}
    client.post("/api/langbot/send_file", json=payload)
    second = client.post("/api/langbot/send_file", json=payload)

    assert napcat.call_count == 1
    assert second.json()["data"]["messageId"] == "f4"


@respx.mock
def test_napcat_error_returns_500(tmp_path):
    path = make_file(tmp_path)
    respx.post("http://nc/upload_private_file").mock(
        return_value=httpx.Response(502, text="bad gateway"))

    client = TestClient(create_app(make_settings(tmp_path)))
    r = client.post("/api/langbot/send_file", json={
        "target": {"type": "private", "id": 1001}, "fileRef": f"local://{path}"})

    assert r.status_code == 500
    assert "发送失败" in r.json()["message"]


# --- I1: 只有「确定性拒绝」才允许回落 NapCat ---

@respx.mock
def test_langbot_5xx_does_not_fall_back_to_napcat(tmp_path):
    """500 可能已经送达；再走 NapCat 就是重复发文件。"""
    path = make_file(tmp_path)
    respx.post("http://lb/api/v1/platform/bots/u1/send_message").mock(
        return_value=httpx.Response(500, text="boom"))
    napcat = respx.post("http://nc/upload_private_file").mock(
        return_value=httpx.Response(200, json={"status": "ok", "data": {"message_id": "dup"}}))

    client = TestClient(create_app(make_settings(tmp_path, file_via_langbot=True)))
    r = client.post("/api/langbot/send_file", json={
        "target": {"type": "private", "id": 1001}, "fileRef": f"local://{path}"})

    assert r.status_code == 500
    assert napcat.call_count == 0


@respx.mock
def test_langbot_timeout_does_not_fall_back_to_napcat(tmp_path):
    path = make_file(tmp_path)
    respx.post("http://lb/api/v1/platform/bots/u1/send_message").mock(
        side_effect=httpx.ReadTimeout("timeout"))
    napcat = respx.post("http://nc/upload_private_file").mock(
        return_value=httpx.Response(200, json={"status": "ok", "data": {"message_id": "dup"}}))

    client = TestClient(create_app(make_settings(tmp_path, file_via_langbot=True)))
    r = client.post("/api/langbot/send_file", json={
        "target": {"type": "private", "id": 1001}, "fileRef": f"local://{path}"})

    assert r.status_code == 500
    assert napcat.call_count == 0


# --- I4: 幂等判断必须先于文件存在性检查 ---

@respx.mock
def test_replay_returns_cached_id_when_local_file_gone(tmp_path):
    path = make_file(tmp_path)
    napcat = respx.post("http://nc/upload_private_file").mock(
        return_value=httpx.Response(200, json={"status": "ok", "data": {"message_id": "f9"}}))
    respx.post("http://sb/api/message-logs").mock(
        return_value=httpx.Response(200, json={"code": 0, "message": "ok", "data": 1}))

    client = TestClient(create_app(make_settings(tmp_path)))
    payload = {"target": {"type": "private", "id": 1001},
               "fileRef": f"local://{path}", "bizRef": "draft:9"}
    assert client.post("/api/langbot/send_file", json=payload).status_code == 200

    path.unlink()  # 产物被清理后再重试确认
    r = client.post("/api/langbot/send_file", json=payload)

    assert r.status_code == 200
    assert r.json()["data"]["messageId"] == "f9"
    assert napcat.call_count == 1
