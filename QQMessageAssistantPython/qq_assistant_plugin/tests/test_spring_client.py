import json

import respx, httpx, pytest
from core.spring_client import SpringClient

@respx.mock
@pytest.mark.asyncio
async def test_check_noise_posts_to_agent_endpoint():
    route = respx.post("http://sb/api/agent/noise-filter/check").mock(
        return_value=httpx.Response(200, json={
            "code": 0, "message": "ok",
            "data": {"noise": True, "reason": "punctuation"}}))
    client = SpringClient("http://sb")

    data = await client.check_noise("……", "text")

    assert data == {"noise": True, "reason": "punctuation"}
    body = json.loads(route.calls[0].request.content)
    assert body == {"text": "……", "msgType": "text"}

@respx.mock
@pytest.mark.asyncio
async def test_non_zero_code_raises():
    respx.get("http://sb/api/profile/1").mock(
        return_value=httpx.Response(200, json={"code": 400, "message": "bad", "data": None}))
    client = SpringClient("http://sb")
    with pytest.raises(RuntimeError, match="bad"):
        await client.profile(1)


@respx.mock
@pytest.mark.asyncio
async def test_resolve_conversation_omits_zero_chat_id():
    route = respx.get("http://sb/api/conversations/resolve").mock(
        return_value=httpx.Response(200, json={
            "code": 0, "message": "ok", "data": {"conversationId": "conv-1"}}))
    client = SpringClient("http://sb")

    assert await client.resolve_conversation("bot", "private", 0, 1001) == "conv-1"
    params = route.calls[0].request.url.params
    assert params["botUuid"] == "bot"
    assert params["chatType"] == "private"
    assert params["senderId"] == "1001"
    assert "chatId" not in params


@respx.mock
@pytest.mark.asyncio
async def test_bind_conversation_payload_keeps_group_chat_id():
    route = respx.post("http://sb/api/conversations/bind").mock(
        return_value=httpx.Response(200, json={"code": 0, "message": "ok", "data": None}))
    client = SpringClient("http://sb")

    await client.bind_conversation("bot", "group", 555, 1001, "conv-1")

    body = json.loads(route.calls[0].request.content)
    assert body == {
        "botUuid": "bot", "chatType": "group", "chatId": 555,
        "senderId": 1001, "conversationId": "conv-1",
    }


@respx.mock
@pytest.mark.asyncio
async def test_list_open_memos_passes_sender_and_limit():
    route = respx.get("http://sb/api/memos/open").mock(
        return_value=httpx.Response(200, json={
            "code": 0, "message": "ok",
            "data": [{"id": 12, "title": "班会", "status": "ACTIVE"}]}))
    client = SpringClient("http://sb")

    data = await client.list_open_memos(1001)

    assert data[0]["id"] == 12
    params = route.calls[0].request.url.params
    assert params["senderId"] == "1001"
    assert params["limit"] == "20"


@respx.mock
@pytest.mark.asyncio
async def test_reply_context_passes_the_three_params():
    route = respx.get("http://sb/api/chat-settings/reply-context").mock(
        return_value=httpx.Response(200, json={
            "code": 0, "message": "ok",
            "data": {"enabled": True, "replyPrompt": "随意点", "rateLimit": 20,
                     "outLastHour": 3, "recentMessages": []}}))
    client = SpringClient("http://sb")

    data = await client.reply_context(123, "GROUP", limit=15)

    assert data["enabled"] is True
    params = route.calls[0].request.url.params
    assert params["chatId"] == "123"
    assert params["kind"] == "GROUP"
    assert params["limit"] == "15"


@respx.mock
@pytest.mark.asyncio
async def test_record_token_usage_posts_to_agent_endpoint():
    route = respx.post("http://sb/api/agent/token-usage").mock(
        return_value=httpx.Response(200, json={"code": 0, "message": "ok", "data": 9}))
    client = SpringClient("http://sb")

    assert await client.record_token_usage({
        "source": "plugin", "task": "chat", "totalTokens": 15,
    }) == 9
    body = json.loads(route.calls[0].request.content)
    assert body == {"source": "plugin", "task": "chat", "totalTokens": 15}
