"""Task 8：备忘 QQ 闭环测试。

classify：只认整句精确匹配（防误伤「完成作业了吗」）。
handle：查 AWAITING_REPLY → 回退 REMINDED → 取 id 最大 → PATCH。
"""
from unittest.mock import AsyncMock, patch

import pytest

from core.closed_loop import classify, handle


# ---------- classify ----------

def test_classify_done_words():
    assert classify("完成") == "DONE"
    assert classify("已完成") == "DONE"
    assert classify("done") == "DONE"
    assert classify(" Done ") == "DONE"      # 大小写 + 首尾空白


def test_classify_cancel_words():
    assert classify("取消") == "CANCELLED"
    assert classify("不去了") == "CANCELLED"
    assert classify("CANCEL") == "CANCELLED"


def test_classify_normal_sentence_is_none():
    # 整句匹配：这些绝不能触发闭环，要正常进 Dify
    assert classify("完成作业了吗") is None
    assert classify("帮我取消这个想法") is None
    assert classify("在吗") is None


# ---------- handle ----------

@pytest.fixture
def deps():
    d = AsyncMock()
    d.spring.list_memos = AsyncMock(return_value=[])
    d.spring.patch_memo = AsyncMock()
    return d


@pytest.mark.asyncio
async def test_handle_picks_latest_awaiting_reply(deps):
    deps.spring.list_memos = AsyncMock(
        side_effect=lambda s, status: [
            {"id": 3, "title": "旧提醒"},
            {"id": 7, "title": "新提醒"},
        ] if status == "AWAITING_REPLY" else [])
    ctx = object()

    with patch("core.closed_loop.reply_text") as reply:
        await handle("完成", 1001, ctx, deps)
        reply.assert_awaited_once_with(ctx, "已完成：新提醒")

    deps.spring.patch_memo.assert_awaited_once_with(7, {"status": "DONE"})


@pytest.mark.asyncio
async def test_handle_falls_back_to_reminded(deps):
    async def fake_list(sender_id, status):
        return [{"id": 5, "title": "已提醒的"}] if status == "REMINDED" else []
    deps.spring.list_memos = AsyncMock(side_effect=fake_list)

    with patch("core.closed_loop.reply_text"):
        await handle("取消", 1001, object(), deps)

    deps.spring.patch_memo.assert_awaited_once_with(5, {"status": "CANCELLED"})


@pytest.mark.asyncio
async def test_handle_no_memo_replies_not_found(deps):
    ctx = object()
    with patch("core.closed_loop.reply_text") as reply:
        await handle("完成", 1001, ctx, deps)
        reply.assert_awaited_once_with(ctx, "没有找到待收尾的提醒～")

    deps.spring.patch_memo.assert_not_awaited()
