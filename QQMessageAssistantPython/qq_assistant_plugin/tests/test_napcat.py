"""NapCat 客户端的契约：action 名、参数名、失败判定。

参数名写错不会报错，只会拿到空历史——补拉于是「跑完了但什么都没补」，
比直接失败更难查。所以这里逐个钉住。
"""
import json

import httpx
import pytest
import respx

from core.napcat import NapcatClient


@respx.mock
@pytest.mark.asyncio
async def test_online_reads_the_status_flag():
    route = respx.post("http://nc/get_status").mock(
        return_value=httpx.Response(200, json={"status": "ok", "retcode": 0,
                                               "data": {"online": True, "good": True}}))
    client = NapcatClient("http://nc")

    assert await client.online() is True
    assert route.calls[0].request.url.path == "/get_status"


@respx.mock
@pytest.mark.asyncio
async def test_offline_status_is_false():
    respx.post("http://nc/get_status").mock(
        return_value=httpx.Response(200, json={"status": "ok", "data": {"online": False}}))

    assert await NapcatClient("http://nc").online() is False


@respx.mock
@pytest.mark.asyncio
async def test_group_history_sends_group_id_count_and_seq():
    route = respx.post("http://nc/get_group_msg_history").mock(
        return_value=httpx.Response(200, json={"status": "ok", "data": {"messages": [
            {"message_id": 11, "message_seq": 900},
        ]}}))

    messages = await NapcatClient("http://nc").group_history(660001, 40, 0)

    body = json.loads(route.calls[0].request.content)
    # 参数名必须是 OneBot 11 的 group_id / count / message_seq，写错就静默返回空
    assert body == {"group_id": 660001, "count": 40, "message_seq": 0}
    assert messages[0]["message_seq"] == 900


@respx.mock
@pytest.mark.asyncio
async def test_friend_history_sends_user_id():
    route = respx.post("http://nc/get_friend_msg_history").mock(
        return_value=httpx.Response(200, json={"status": "ok", "data": {"messages": []}}))

    assert await NapcatClient("http://nc").friend_history(7001, 40, 120) == []

    body = json.loads(route.calls[0].request.content)
    assert body == {"user_id": 7001, "count": 40, "message_seq": 120}


@respx.mock
@pytest.mark.asyncio
async def test_business_failure_is_an_error_even_on_http_200():
    """OneBot 的约定是 HTTP 200 + status=failed。

    只看状态码会把「群号不存在」当成「这个群没有历史消息」，然后静默跳过。
    """
    respx.post("http://nc/get_group_msg_history").mock(
        return_value=httpx.Response(200, json={"status": "failed", "message": "群不存在"}))

    with pytest.raises(RuntimeError, match="群不存在"):
        await NapcatClient("http://nc").group_history(1, 40, 0)


@respx.mock
@pytest.mark.asyncio
async def test_token_is_sent_as_bearer():
    route = respx.post("http://nc/get_status").mock(
        return_value=httpx.Response(200, json={"status": "ok", "data": {"online": True}}))

    await NapcatClient("http://nc", token="secret").online()

    assert route.calls[0].request.headers["Authorization"] == "Bearer secret"


@respx.mock
@pytest.mark.asyncio
async def test_403_explains_the_token_problem():
    respx.post("http://nc/get_status").mock(
        return_value=httpx.Response(403, text="forbidden"))

    with pytest.raises(RuntimeError, match="token 未配置"):
        await NapcatClient("http://nc").online()


@pytest.mark.asyncio
async def test_unconfigured_client_refuses_instead_of_hitting_localhost():
    client = NapcatClient("")

    assert client.configured is False
    with pytest.raises(RuntimeError, match="napcat_api_url"):
        await client.online()


@respx.mock
@pytest.mark.asyncio
async def test_online_without_status_flag_is_still_online():
    """兼容实现不带 online/good 时，不能让 `_was_online` 永远停在 False。"""
    respx.post("http://nc/get_status").mock(
        return_value=httpx.Response(200, json={
            "status": "ok", "data": {"app_version": "4.0"}}))

    assert await NapcatClient("http://nc").online() is True


@respx.mock
@pytest.mark.asyncio
async def test_empty_status_data_is_not_online():
    respx.post("http://nc/get_status").mock(
        return_value=httpx.Response(200, json={"status": "ok", "data": {}}))

    assert await NapcatClient("http://nc").online() is False


@respx.mock
@pytest.mark.asyncio
async def test_good_flag_is_used_when_online_is_absent():
    respx.post("http://nc/get_status").mock(
        return_value=httpx.Response(200, json={"status": "ok", "data": {"good": False}}))

    assert await NapcatClient("http://nc").online() is False


@respx.mock
@pytest.mark.asyncio
async def test_account_online_reads_a_real_user_id():
    route = respx.post("http://nc/get_login_info").mock(
        return_value=httpx.Response(200, json={
            "status": "ok", "data": {"user_id": 123456, "nickname": "bot"}}))

    assert await NapcatClient("http://nc").account_online() is True
    assert route.calls[0].request.url.path == "/get_login_info"


@respx.mock
@pytest.mark.asyncio
async def test_account_online_is_false_when_login_expired():
    """登录过期时 NapCat HTTP 还在，但拿不到有效 user_id。"""
    respx.post("http://nc/get_login_info").mock(
        return_value=httpx.Response(200, json={"status": "ok", "data": {"user_id": 0}}))

    assert await NapcatClient("http://nc").account_online() is False


@respx.mock
@pytest.mark.asyncio
async def test_account_online_is_false_with_empty_login_info():
    respx.post("http://nc/get_login_info").mock(
        return_value=httpx.Response(200, json={"status": "ok", "data": {}}))

    assert await NapcatClient("http://nc").account_online() is False
