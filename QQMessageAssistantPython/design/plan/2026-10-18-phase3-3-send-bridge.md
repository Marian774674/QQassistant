# Phase 3-3：主动发送桥（Spring Boot → LangBot）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 `api_contract.md` 第十二节的两个稳定契约 `/api/langbot/send_message` 与 `/api/langbot/send_file`，让 Spring Boot 的「提醒推送」「草稿确认发送」真正能发到 QQ。

**Architecture:** 独立的 Python 服务 `send_bridge`（FastAPI，默认 `127.0.0.1:5301`）。它是 **LangBot 层的一部分**，不是 Spring Boot 的一部分，所以不违反「Spring Boot 不直连 NapCat」的红线。文本转发给 LangBot 官方 HTTP API；文件优先尝试 LangBot，官方通道不支持 `File` 时回落 NapCat OneBot 11。

**Tech Stack:** FastAPI / uvicorn / httpx / pytest + respx。

## Global Constraints

- 端口 `5301`；只监听 `127.0.0.1`。
- 发送**绝不自动重试**（宁可失败也不能重复发消息）。
- 所有上游错误都要转成契约响应体，HTTP 状态码与 `code` 一致。
- `bizRef` 相同的请求 24h 内幂等：直接返回上次的 `messageId`，不重发。

---

## 〇、为什么是独立桥，而不是插件里开 HTTP

| 方案 | 结论 |
|---|---|
| 插件进程内起 HTTP server | 文档未支持；插件随 LangBot 启停，调试、重启、独立部署都别扭 |
| 改 Spring Boot 直接调 LangBot 官方 API | 官方 API 的 `send_message` 文档只列 `Plain/Image/At/Voice`，**文件发送没有保证**；且会把 `bot_uuid`/API Key/延迟逻辑塞回 Java |
| **独立 Python 桥（本方案）** | 契约稳定、可 curl 测、文本与文件两条上游可独立替换、延迟与 OUT 日志集中一处 |

Spring Boot 只改一个配置：`langbot.base-url: http://localhost:5301`，**Java 代码零改动**。

---

## Task 1: 服务骨架与配置

**Files:**
- Create: `QQMessageAssistantPython/send_bridge/app.py`
- Create: `QQMessageAssistantPython/send_bridge/config.py`
- Create: `QQMessageAssistantPython/send_bridge/requirements.txt`
- Create: `QQMessageAssistantPython/send_bridge/.env.example`

**Interfaces:**
- Produces: `create_app(settings) -> FastAPI`

- [ ] **Step 1: 建目录与依赖**

```bash
cd /e/project/assistant/QQMessageAssistantPython
mkdir -p send_bridge/tests
.venv/Scripts/python.exe -m pip install fastapi uvicorn httpx pytest respx
```

`requirements.txt`：

```text
fastapi>=0.115
uvicorn>=0.30
httpx>=0.27
```

- [ ] **Step 2: `config.py`**

```python
import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    host: str = os.getenv("BRIDGE_HOST", "127.0.0.1")
    port: int = int(os.getenv("BRIDGE_PORT", "5301"))

    langbot_base: str = os.getenv("LANGBOT_BASE", "http://localhost:5300").rstrip("/")
    langbot_bot_uuid: str = os.getenv("LANGBOT_BOT_UUID", "")
    langbot_api_key: str = os.getenv("LANGBOT_API_KEY", "")

    napcat_base: str = os.getenv("NAPCAT_BASE", "http://localhost:3000").rstrip("/")
    napcat_token: str = os.getenv("NAPCAT_TOKEN", "")

    spring_base: str = os.getenv("SPRING_BASE", "http://localhost:8080").rstrip("/")

    delay_base_ms: int = int(os.getenv("DELAY_BASE_MS", "1500"))
    delay_per_char_ms: int = int(os.getenv("DELAY_PER_CHAR_MS", "150"))
    delay_jitter_ms: int = int(os.getenv("DELAY_JITTER_MS", "1000"))
    delay_max_ms: int = int(os.getenv("DELAY_MAX_MS", "15000"))
    timeout: float = float(os.getenv("HTTP_TIMEOUT", "10"))

    state_file: str = os.getenv("STATE_FILE", "data/sent.json")


settings = Settings()
```

`.env.example` 逐项列出上述变量并写注释。

- [ ] **Step 3: Commit**

```bash
git add send_bridge
git commit -m "chore(bridge): scaffold active-send bridge service"
```

---

## Task 2: 文本发送（LangBot 官方 API + 拟人延迟 + OUT 日志）

**Files:**
- Create: `send_bridge/upstream.py`
- Create: `send_bridge/app.py`
- Test: `send_bridge/tests/test_send_message.py`

**契约（来自 `api_contract.md` §12.1）：**

```jsonc
POST /api/langbot/send_message
{ "target": { "type": "private|group", "id": 2002 },
  "content": "文本", "delayMs": 0, "bizRef": "memo:123" }
→ { "code": 0, "message": "ok", "data": { "messageId": "..." } }
```

**官方 API 映射：**

```http
POST {LANGBOT_BASE}/api/v1/platform/bots/{bot_uuid}/send_message
X-API-Key: {LANGBOT_API_KEY}
Content-Type: application/json

{ "target_type": "person", "target_id": "2002",
  "message_chain": [ { "type": "Plain", "text": "..." } ] }
```

> `private → person`、`group → group`。这是唯一的映射差异。

- [ ] **Step 1: `upstream.py`**

```python
import asyncio
import random
import httpx


class LangBotUpstream:
    def __init__(self, settings):
        self._s = settings
        self._client = httpx.AsyncClient(timeout=settings.timeout)

    async def close(self):
        await self._client.aclose()

    async def send_text(self, target_type: str, target_id: int, text: str) -> str:
        payload = {
            "target_type": "person" if target_type == "private" else "group",
            "target_id": str(target_id),
            "message_chain": [{"type": "Plain", "text": text}],
        }
        resp = await self._client.post(
            f"{self._s.langbot_base}/api/v1/platform/bots/{self._s.langbot_bot_uuid}/send_message",
            json=payload,
            headers={"X-API-Key": self._s.langbot_api_key},
        )
        resp.raise_for_status()
        body = resp.json()
        return str(body.get("message_id") or body.get("messageId") or "")


def delay_ms(text: str, requested: int | None, s) -> int:
    if requested is not None:
        return max(0, int(requested))
    ms = s.delay_base_ms + len(text) * s.delay_per_char_ms + random.randint(0, s.delay_jitter_ms)
    return min(ms, s.delay_max_ms)


async def human_pause(text: str, requested, s, target_type: str):
    if target_type != "private":
        return                     # 拟人延迟只在私聊
    await asyncio.sleep(delay_ms(text, requested, s) / 1000.0)
```

- [ ] **Step 2: `app.py` 最小实现**

```python
import logging
import httpx
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from .config import Settings, settings as SETTINGS
from .upstream import LangBotUpstream, human_pause

log = logging.getLogger(__name__)


class Target(BaseModel):
    type: str
    id: int


class SendMessageBody(BaseModel):
    target: Target
    content: str
    delayMs: int | None = None
    bizRef: str | None = None


def create_app(s: Settings = SETTINGS) -> FastAPI:
    upstream = LangBotUpstream(s)
    spring = httpx.AsyncClient(base_url=s.spring_base, timeout=s.timeout)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        log.info("send bridge ready on %s:%s", s.host, s.port)
        yield
        await upstream.close()
        await spring.aclose()

    app = FastAPI(title="QQ Assistant Send Bridge", lifespan=lifespan)

    @app.get("/health")
    async def health():
        return {"code": 0, "message": "ok", "data": {"status": "up"}}

    @app.post("/api/langbot/send_message")
    async def send_message(body: SendMessageBody):
        if body.target.type not in ("private", "group"):
            return JSONResponse(status_code=400,
                                content={"code": 400, "message": "target.type 只能是 private 或 group", "data": None})
        if not body.content.strip():
            return JSONResponse(status_code=400,
                                content={"code": 400, "message": "content 不能为空", "data": None})

        try:
            await human_pause(body.content, body.delayMs, s, body.target.type)
            message_id = await upstream.send_text(body.target.type, body.target.id, body.content)
        except Exception as e:
            log.exception("send_message failed")
            return JSONResponse(status_code=500,
                                content={"code": 500, "message": f"发送失败：{e}", "data": None})

        try:
            await spring.post("/api/message-logs", json=[{
                "senderId": body.target.id, "chatId": None, "direction": "OUT",
                "msgType": "text", "content": body.content,
            }])
        except Exception as e:
            log.warning("log OUT failed: %s", e)

        return {"code": 0, "message": "ok", "data": {"messageId": message_id}}

    return app


app = create_app()
```

> 契约要求业务错误返回 **HTTP 400 + `code:400`**，因此统一用 `JSONResponse(status_code=..., content=...)`，不要用 `HTTPException`（它的响应体是 `{"detail": ...}`，Java 侧解析不到 `message`）。

- [ ] **Step 3: respx 测试**

```python
import respx, httpx
from fastapi.testclient import TestClient
from send_bridge.config import Settings
from send_bridge.app import create_app

TEST = Settings(langbot_base="http://lb", langbot_bot_uuid="u1", langbot_api_key="k",
                spring_base="http://sb", delay_base_ms=0, delay_per_char_ms=0,
                delay_jitter_ms=0, delay_max_ms=0)

@respx.mock
def test_maps_private_to_person_and_logs_out():
    respx.post("http://lb/api/v1/platform/bots/u1/send_message").mock(
        return_value=httpx.Response(200, json={"status": "success", "message_id": "m1"}))
    logs = respx.post("http://sb/api/message-logs").mock(
        return_value=httpx.Response(200, json={"code": 0, "message": "ok", "data": 1}))

    client = TestClient(create_app(TEST))
    r = client.post("/api/langbot/send_message", json={
        "target": {"type": "private", "id": 1001}, "content": "在吗", "bizRef": "memo:1"})

    assert r.json() == {"code": 0, "message": "ok", "data": {"messageId": "m1"}}
    sent = respx.calls[0].request
    body = __import__("json").loads(sent.content)
    assert body["target_type"] == "person"
    assert body["message_chain"][0]["text"] == "在吗"
    assert logs.call_count == 1
```

- [ ] **Step 4: 跑测试**

```bash
cd send_bridge
../../.venv/Scripts/python.exe -m pytest tests -q
```

- [ ] **Step 5: Commit**

```bash
git add send_bridge
git commit -m "feat(bridge): send_message with delay, LangBot mapping, OUT logging"
```

---

## Task 3: 文件发送

**Files:**
- Modify: `send_bridge/upstream.py`
- Modify: `send_bridge/app.py`
- Test: `send_bridge/tests/test_send_file.py`

**先做一次 15 分钟的探针（决定走哪条路）：**

```bash
curl -s -X POST http://localhost:5300/api/v1/platform/bots/<bot_uuid>/send_message \
  -H "X-API-Key: <key>" -H "Content-Type: application/json" \
  -d '{"target_type":"person","target_id":"<你的QQ>","message_chain":[{"type":"File","name":"t.txt","url":"/absolute/path/t.txt"}]}'
```

- 若手机能收到文件 → 文件也走 LangBot，`File` 字段按实际报错补齐（可能是 `file`/`path`）。
- 若被拒（大概率，文档未列 `File`）→ 走 NapCat。

**NapCat 回落：**

```http
POST {NAPCAT_BASE}/upload_private_file
Authorization: Bearer {NAPCAT_TOKEN}
{ "user_id": 1001, "file": "/abs/path/out.xlsx", "name": "out.xlsx" }

POST {NAPCAT_BASE}/upload_group_file
{ "group_id": 2002, "file": "/abs/path/out.xlsx", "name": "out.xlsx", "folder": "/" }
```

- [ ] **Step 1: 实现 `send_file`**

```python
async def send_file(self, target_type: str, target_id: int, file_ref: str, file_name: str | None) -> str:
    path = file_ref.removeprefix("local://")
    if self.file_via_langbot:
        payload = {
            "target_type": "person" if target_type == "private" else "group",
            "target_id": str(target_id),
            "message_chain": [{"type": "File", "name": file_name or path.split("/")[-1],
                               "url": path}],
        }
        resp = await self._client.post(...)      # 同 send_text 的 URL/headers
        ...
    else:
        action = "upload_private_file" if target_type == "private" else "upload_group_file"
        key = "user_id" if target_type == "private" else "group_id"
        resp = await self._client.post(
            f"{self._s.napcat_base}/{action}",
            json={key: target_id, "file": path, "name": file_name or path.split("/")[-1]},
            headers={"Authorization": f"Bearer {self._s.napcat_token}"},
        )
        resp.raise_for_status()
        return str(resp.json().get("data", {}).get("message_id", ""))
```

- [ ] **Step 2: `app.py` 加端点**，响应体与 §12.2 一致；文件不存在直接 400（`os.path.isfile` 检查）。
- [ ] **Step 3: 真机冒烟**：`curl` 发一个本地 xlsx 到自己的 QQ。
- [ ] **Step 4: Commit**

```bash
git add send_bridge
git commit -m "feat(bridge): send_file via LangBot with NapCat fallback"
```

---

## Task 4: bizRef 幂等

**Files:**
- Create: `send_bridge/idempotency.py`
- Test: `send_bridge/tests/test_idempotency.py`

**规则：**

- key = `bizRef`；记录 `{messageId, ts}`。
- 24h 内重复 → 直接返回缓存，不发送。
- 落盘到 `data/sent.json`，写入用「临时文件 + `os.replace`」保证原子性。
- 无 `bizRef` 不启用幂等。
- 进程启动时清理超过 48h 的条目。

- [ ] **Step 1: 实现**

```python
import json
import os
import time
from pathlib import Path


class IdempotencyStore:
    def __init__(self, path: str, ttl: int = 86400):
        self.path = Path(path)
        self.ttl = ttl
        self._data = {}
        if self.path.exists():
            try:
                self._data = json.loads(self.path.read_text(encoding="utf-8"))
            except Exception:
                self._data = {}
        now = time.time()
        self._data = {k: v for k, v in self._data.items() if now - v["ts"] < ttl * 2}
        self._flush()

    def get(self, key: str | None):
        if not key:
            return None
        v = self._data.get(key)
        if v and time.time() - v["ts"] < self.ttl:
            return v["messageId"]
        return None

    def put(self, key: str | None, message_id: str):
        if not key:
            return
        self._data[key] = {"messageId": message_id, "ts": time.time()}
        self._flush()

    def _flush(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self._data, ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, self.path)
```

- [ ] **Step 2: 两个端点接入**：先 `get` 命中即返回；发送成功后 `put`。
- [ ] **Step 3: 测试 + Commit**

```bash
../../.venv/Scripts/python.exe -m pytest tests/test_idempotency.py -q
git add send_bridge
git commit -m "feat(bridge): 24h bizRef idempotency"
```

---

## Task 5: 联调 Spring Boot

**Files:**
- Modify: `QQMessageAssistantJava/src/main/resources/application.yaml`

- [ ] **Step 1: 改指向**

```yaml
langbot:
  base-url: http://localhost:5301
  connect-timeout-ms: 2000
  read-timeout-ms: 20000     # 必须 > 桥侧 DELAY_MAX_MS(15000) + 网络余量
```

> **关键联动**：桥内的拟人延迟发生在 HTTP 请求返回之前。若读超时小于 `DELAY_MAX_MS`，长消息必然超时，Java 会回滚草稿状态。
> - **本方案（简单）**：读超时 20s，接受草稿确认接口最长约 20 秒返回。个人项目够用。
> - **后续升级（并发变高再做）**：桥收到请求后立即返回 `taskId`，后台延迟发送；需要给契约增加 `async: true` 与 `GET /api/langbot/tasks/{id}`。
>
> 文档按前者落地，**不要**先把超时设成 3s 再改，否则中间状态是坏的。

- [ ] **Step 2: 起两个进程**

```bash
# 终端 1
cd QQMessageAssistantPython/send_bridge
../../.venv/Scripts/python.exe -m uvicorn app:app --host 127.0.0.1 --port 5301

# 终端 2
cd QQMessageAssistantJava
mvn spring-boot:run
```

- [ ] **Step 3: 三条端到端冒烟**

```bash
# 1) 直接打桥
curl -s -X POST http://localhost:5301/api/langbot/send_message \
  -H "Content-Type: application/json" \
  -d '{"target":{"type":"private","id":<你的QQ>},"content":"桥测试","bizRef":"smoke:1"}'

# 2) 打桥两次，验证幂等（第二次不应有第二条 QQ 消息）
# 3) 走 Spring Boot 的调度链路：手工插一条 remind_at 已过期的 PENDING 提醒，
#    等下一轮 scan，确认 QQ 收到推送且 memo_reminder.status=SENT
```

- [ ] **Step 4: Commit**

```bash
git add QQMessageAssistantJava/src/main/resources/application.yaml
git commit -m "chore(java): point LangBot client to local send bridge"
```

---

## 验收清单

- [ ] `send_message` 私聊有 1.5s+ 延迟，群聊不延迟
- [ ] OUT 日志能在 `message_log` 查到
- [ ] 同一 `bizRef` 重复调用只发一次
- [ ] `send_file` 手机端能收到，文件名正确
- [ ] 提醒推送链路：`memo_reminder` → 桥 → QQ，全链路一次跑通
- [ ] 草稿中心确认 REPLY/FILE 草稿 → QQ 真实收到
