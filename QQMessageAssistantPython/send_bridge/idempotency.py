"""bizRef 幂等：同一业务引用的发送只执行一次。

- key = bizRef；记录 {messageId, ts}
- TTL 内重复 → 返回上次的 messageId，不再发送
- 落盘用「临时文件 + os.replace」保证原子性
- 无 bizRef 不启用幂等
"""

import json
import os
import time
from pathlib import Path

DEFAULT_TTL = 86400


class IdempotencyStore:
    def __init__(self, path: str, ttl: int = DEFAULT_TTL):
        self.path = Path(path)
        self.ttl = ttl
        self._data: dict[str, dict] = {}
        # 正在发送中的 key（仅进程内）：并发重复请求不得各发一次
        self._inflight: set[str] = set()
        if self.path.exists():
            try:
                loaded = json.loads(self.path.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    self._data = loaded
            except Exception:
                self._data = {}
        now = time.time()
        # 启动时清掉超过 2*ttl 的陈旧条目（保留一点余量便于排查）
        self._data = {k: v for k, v in self._data.items()
                      if isinstance(v, dict) and now - v.get("ts", 0) < ttl * 2}
        self._flush()

    def get(self, key: str | None) -> str | None:
        if not key:
            return None
        entry = self._data.get(key)
        if entry and time.time() - entry.get("ts", 0) < self.ttl:
            return entry.get("messageId")
        return None

    def reserve(self, key: str | None) -> bool:
        """占位：已发送（TTL 内）或正在发送时返回 False，调用方不得再发。"""
        if not key:
            return True
        if self.get(key) is not None or key in self._inflight:
            return False
        self._inflight.add(key)
        return True

    def release(self, key: str | None) -> None:
        """发送失败后释放占位，允许调用方重试。"""
        if key:
            self._inflight.discard(key)

    def put(self, key: str | None, message_id: str) -> None:
        if not key:
            return
        self._data[key] = {"messageId": message_id, "ts": time.time()}
        self._inflight.discard(key)
        self._flush()

    def _flush(self) -> None:
        # 没有数据且文件还不存在：不落盘，避免空跑时凭空建出 data 目录
        if not self._data and not self.path.exists():
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self._data, ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, self.path)
