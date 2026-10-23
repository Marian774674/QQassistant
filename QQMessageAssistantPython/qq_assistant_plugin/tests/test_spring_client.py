import respx, httpx, pytest
from core.spring_client import SpringClient

@respx.mock
@pytest.mark.asyncio
async def test_check_replied_unwraps_data():
    respx.post("http://sb/api/collection-tasks/check").mock(
        return_value=httpx.Response(200, json={"code": 0, "message": "ok", "data": {"replied": True}}))
    client = SpringClient("http://sb")
    assert await client.check_replied(1, "学号收集", "k") is True

@respx.mock
@pytest.mark.asyncio
async def test_non_zero_code_raises():
    respx.get("http://sb/api/profile/1").mock(
        return_value=httpx.Response(200, json={"code": 400, "message": "bad", "data": None}))
    client = SpringClient("http://sb")
    with pytest.raises(RuntimeError, match="bad"):
        await client.profile(1)
