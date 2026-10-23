"""日志队列测试：重点盯 flush 是否真的调到了 SpringClient 的正确方法。

教训：log_message(单数) 与 log_messages(复数) 名字不符时，异常被 _flush 吞掉，
表现为「回复正常但库中无日志」。断言 mock 被调用是唯一可靠的守门员。
"""
from unittest.mock import AsyncMock

import pytest

from core.logging_queue import MessageLogQueue


@pytest.fixture
def spring():
    return AsyncMock()


def make_item(i):
    return {"senderId": 1001, "chatId": None, "direction": "IN",
            "msgType": "text", "content": f"msg-{i}"}


@pytest.mark.asyncio
async def test_flush_sends_batch_with_correct_method(spring):
    q = MessageLogQueue(spring, batch=20)
    q.enqueue(make_item(1))
    q.enqueue(make_item(2))

    await q._flush()

    # 方法名必须是 log_messages（复数），且一次批量调用两条
    spring.log_messages.assert_awaited_once()
    sent = spring.log_messages.await_args.args[0]
    assert [i["content"] for i in sent] == ["msg-1", "msg-2"]
    assert len(q._q) == 0


@pytest.mark.asyncio
async def test_flush_retries_then_drops_on_failure(spring):
    spring.log_messages = AsyncMock(side_effect=RuntimeError("spring down"))
    q = MessageLogQueue(spring, batch=20)
    q.enqueue(make_item(1))

    await q._flush()

    # 3 次重试后丢弃，不无限阻塞队列
    assert spring.log_messages.await_count == 3
    assert len(q._q) == 0


def test_enqueue_drops_oldest_when_full():
    q = MessageLogQueue(AsyncMock(), maxlen=3)
    for i in range(5):
        q.enqueue(make_item(i))
    assert [i["content"] for i in q._q] == ["msg-2", "msg-3", "msg-4"]
