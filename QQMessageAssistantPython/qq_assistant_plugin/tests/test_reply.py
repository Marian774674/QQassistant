"""本轮静默：总开关关掉时，任何 ``reply_text`` 都变成空操作。

放在最底层是因为直接回话的地方散在 file_task / dispatch / 拦截分支里，
逐个调用点判断必然漏。这里钉住机制本身。
"""
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from core.reply import is_silenced, reply_text, silence


@pytest.mark.asyncio
async def test_reply_text_sends_by_default():
    ctx = SimpleNamespace(reply=AsyncMock())

    await reply_text(ctx, "在呢")

    ctx.reply.assert_awaited_once()


@pytest.mark.asyncio
async def test_silence_turns_reply_text_into_a_noop():
    ctx = SimpleNamespace(reply=AsyncMock())

    with silence():
        assert is_silenced() is True
        await reply_text(ctx, "不该发出去")

    ctx.reply.assert_not_awaited()
    assert is_silenced() is False


@pytest.mark.asyncio
async def test_silence_is_scoped_and_nestable():
    ctx = SimpleNamespace(reply=AsyncMock())

    with silence():
        with silence():
            await reply_text(ctx, "不发")
        await reply_text(ctx, "也不发")
    await reply_text(ctx, "这条要发")

    assert ctx.reply.await_count == 1
