"""前置噪音判定的边界：规则在 Spring 侧，插件只负责 fail-open。

拦截规则下线后，这个位置由 /api/agent/noise-filter/check 接管；
判定接口拿不到结果时一律放行，绝不能把主人的话吞掉。
"""
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from components.event_listener.default import _is_noise
from core import file_store


@pytest.fixture(autouse=True)
def no_debug(monkeypatch):
    monkeypatch.setattr(file_store, "debug", lambda *a, **k: None)


def _deps(check_noise):
    return SimpleNamespace(spring=SimpleNamespace(check_noise=check_noise))


@pytest.mark.asyncio
async def test_noise_true_short_circuits():
    check = AsyncMock(return_value={"noise": True, "reason": "punctuation"})
    assert await _is_noise(_deps(check), "……") is True
    check.assert_awaited_once_with("……", "text")


@pytest.mark.asyncio
async def test_noise_false_keeps_message():
    check = AsyncMock(return_value={"noise": False, "reason": ""})
    assert await _is_noise(_deps(check), "明天班会") is False


@pytest.mark.asyncio
async def test_empty_response_keeps_message():
    check = AsyncMock(return_value=None)
    assert await _is_noise(_deps(check), "x") is False


@pytest.mark.asyncio
async def test_exception_keeps_message():
    check = AsyncMock(side_effect=RuntimeError("spring down"))
    assert await _is_noise(_deps(check), "x") is False


@pytest.mark.asyncio
async def test_old_spring_without_endpoint_keeps_message():
    # Spring 还没升级、连方法都没有时也不能吞消息
    assert await _is_noise(SimpleNamespace(spring=SimpleNamespace()), "x") is False
