import asyncio
import logging
from collections import deque

log = logging.getLogger(__name__)


class MessageLogQueue:
    def __init__(self, spring, maxlen=1000, batch=20, interval=1.0):
        self._spring = spring
        self._q = deque()
        self._maxlen = maxlen
        self._batch = batch
        self._interval = interval
        self._task = None

    def enqueue(self,item: dict):
        if len(self._q) >= self._maxlen:
            self._q.popleft()
            log.warning("Message log queue is full, dropping the oldest item")
        self._q.append(item)

    def start(self):
        self._task = asyncio.create_task(self._run())

    async def stop(self):
        if self._task:
            self._task.cancel()
        await self._flush()

    async def _run(self):
        while True:
            await asyncio.sleep(self._interval)
            await self._flush()

    async def _flush(self):
        while self._q:
            batch = [self._q.popleft() for _ in range(min(self._batch,len(self._q)))]
            for attempt in range(3):
                try:
                    await self._spring.log_messages(batch)
                    break
                except Exception as e:
                    if attempt == 2:
                        log.error("failed to flush %d logs: %s", len(batch), e)
                    else:
                        await asyncio.sleep(0.5 * (attempt + 1))