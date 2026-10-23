# Phase 3-1：LangBot 插件核心管线 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 LangBot 里做一个插件，接管「收消息 → 记日志 → 前置拦截 → 调 Dify → 拟人延迟 → 发送/建草稿 → QQ 内备忘闭环」的整条链路。

**Architecture:** **插件是编排者**，不使用 LangBot 内置的 Dify 对话管线。插件监听 `PersonMessageReceived` / `GroupMessageReceived`，先 `prevent_default()` + `prevent_postorder()` 掐断内置管线，再自己调 Dify、Spring Boot 和平台发送 API。理由：拟人延迟、草稿降级、拦截 bypass、双端闭环都需要对「发不发、何时发、发什么」有完全控制权，内置管线给不了。

**Tech Stack:** Python 3.10+ / `langbot_plugin` SDK / httpx / asyncio / pytest + respx。

## Global Constraints

- 插件目录：`QQMessageAssistantPython/qq_assistant_plugin/`。
- 依赖安装在 `QQMessageAssistantPython/.venv`，不要用全局 python。
- 所有对 Spring Boot 的写操作必须**失败不阻塞回复**（记日志、同步镜像）；读操作（画像、规则、备忘列表）失败按降级策略处理。
- 发送动作**绝不重试**（避免重复消息）；幂等接口（`check`、`GET`）可以重试。
- 不在插件里实现任何「业务判断」——重要性、草稿与否、填什么，全部来自 Dify 的结构化输出；插件只做机械分发。

---

## 〇、Phase 3 的三个关键决策（先拍板再写码）

### D1：绕过 LangBot 内置管线

监听 `PersonMessageReceived` / `GroupMessageReceived`（**所有**消息，不受触发规则限制），在 handler 第一行：

```python
ctx.prevent_default()
ctx.prevent_postorder()
```

> 注意：只调 `prevent_default()` 不够，必须同时调 `prevent_postorder()` 才能真正拦住 LLM 响应。

后果：LangBot 的「@机器人 / 前缀 / 概率触发」规则不再生效，**群聊是否响应由插件自己判断**：
- 私聊：全部响应（除非是文件/闭环等特殊分支）。
- 群聊：`@机器人`（`At.target == bot_qq`）或以配置前缀（默认 `!`）开头才进 Dify；其余消息只记日志 + 跑前置拦截。

### D2：Dify ↔ 插件的结构化协议

Dify 的 `answer` 必须是一段 JSON（Dify 系统提示里强制）。插件按 `action` 机械分发：

```jsonc
{
  "action": "SEND",                  // SEND | DRAFT | SILENT | MEMO | FACT | FILE_TASK
  "content": "最终回复文本",          // SEND / MEMO 时作为 QQ 回复
  "confidence": 0.93,
  "draft": { "type": "REPLY|MEMO|FACT|FILE", "title": "…", "payload": { } },
  "memo":  { "title": "…", "content": "…", "importance": 3, "category": "学业", "dueTime": "2026-10-20T19:00:00" },
  "fact":  { "factKey": "专业", "factValue": "计算机科学与技术", "confidence": 0.93 },
  "file":  { "taskRef": "ft_0001", "request": "填学号、姓名" }
}
```

分发规则：

| action | 插件动作 |
|---|---|
| `SEND` | 拟人延迟 → `ctx.reply(Plain(content))` → 记 OUT 日志 |
| `DRAFT` | `POST /api/drafts`，QQ 不回 |
| `SILENT` | 什么都不做 |
| `MEMO` | `POST /api/memos`；有 `content` 则按 SEND 回复 |
| `FACT` | 置信度 ≥ 阈值 → `PUT /api/profile/facts`；否则降级成 `DRAFT(type=FACT)` |
| `FILE_TASK` | 交给 Phase 3-2 的文件模块 |

JSON 解析失败 → **安全降级**：把整段 answer 当 `SEND` 文本发出去（不能让用户收不到回复）。

### D3：备忘 QQ 闭环不靠内存映射

`optimization_schemes.md` 写的是「LangBot 维护 `memo_id → 用户` 映射」。**改成查 Spring Boot**：

```
用户私聊发「完成」/「取消」
→ GET /api/memos?senderId={sender}&status=AWAITING_REPLY
→ 为空再查 status=REMINDED
→ 取 id 最大的一条 → PATCH /api/memos/{id} {"status":"DONE"|"CANCELLED"}
```

理由：插件重启不丢状态；Spring Boot 本来就是状态的唯一真身；省掉一套持久化映射。这是对原设计的**简化**，需在实现时同步到 `optimization_schemes.md` 的决策记录。

---

## 一、文件结构

```
QQMessageAssistantPython/qq_assistant_plugin/
├── main.py                          # BasePlugin：初始化配置、HTTP 客户端、后台刷新
├── manifest.yaml                    # 元信息 + 配置项
├── requirements.txt                 # httpx
├── components/
│   └── event_listener/
│       ├── __init__.py
│       ├── default.py               # 唯一 EventListener
│       └── default.yaml             # 由 lbp comp EventListener 生成
├── core/
│   ├── __init__.py
│   ├── config.py                    # Settings dataclass
│   ├── spring_client.py             # Spring Boot API 封装
│   ├── dify_client.py               # Dify chat-messages 封装
│   ├── text.py                      # 文本提取、@ 判断、延迟公式
│   ├── rules.py                     # 拦截规则缓存 + 匹配
│   ├── closed_loop.py               # 完成/取消 闭环
│   ├── dispatch.py                  # Dify action → Spring 调用/回复
│   └── logging_queue.py             # 异步批量写 message_log
└── tests/
    ├── test_text.py
    ├── test_rules.py
    ├── test_dispatch.py
    └── test_closed_loop.py
```

---

## Task 1: 插件骨架与配置

**Files:**
- Create: `QQMessageAssistantPython/qq_assistant_plugin/`（由 CLI 生成后改）
- Create: `core/config.py`
- Modify: `manifest.yaml`

**Interfaces:**
- Produces: `Settings`（供后续所有 Task 使用）

- [ ] **Step 1: 装依赖并生成骨架**

```bash
cd /e/project/assistant/QQMessageAssistantPython
.venv/Scripts/python.exe -m pip install -U langbot_plugin
.venv/Scripts/python.exe -m langbot_plugin.cli.__init__ init qq_assistant_plugin
cd qq_assistant_plugin
../.venv/Scripts/python.exe -m langbot_plugin.cli.__init__ comp EventListener
```

若 `lbp` 可用，直接用 `lbp init qq_assistant_plugin` 与 `lbp comp EventListener`。

生成后确认存在：`main.py`、`manifest.yaml`、`components/event_listener/default.{py,yaml}`。

- [ ] **Step 2: 写 `core/config.py`**

```python
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    spring_base_url: str
    spring_timeout: float
    dify_base_url: str
    dify_api_key: str
    dify_timeout: float
    bot_uuid: str
    bot_qq: int
    group_prefix: str
    fact_auto_threshold: float
    delay_base_ms: int
    delay_per_char_ms: int
    delay_jitter_ms: int
    delay_max_ms: int
    intercept_refresh_s: int
    intercept_scope: str          # group | all | off


def load_settings(plugin) -> Settings:
    c = plugin.get_config()
    return Settings(
        spring_base_url=str(c.get("spring_base_url", "http://localhost:8080")).rstrip("/"),
        spring_timeout=float(c.get("spring_timeout", 5)),
        dify_base_url=str(c.get("dify_base_url", "http://localhost/v1")).rstrip("/"),
        dify_api_key=str(c.get("dify_api_key", "")),
        dify_timeout=float(c.get("dify_timeout", 60)),
        bot_uuid=str(c.get("bot", "")),
        bot_qq=int(c.get("bot_qq", 0)),
        group_prefix=str(c.get("group_prefix", "!")),
        fact_auto_threshold=float(c.get("fact_auto_threshold", 0.85)),
        delay_base_ms=int(c.get("delay_base_ms", 1500)),
        delay_per_char_ms=int(c.get("delay_per_char_ms", 150)),
        delay_jitter_ms=int(c.get("delay_jitter_ms", 1000)),
        delay_max_ms=int(c.get("delay_max_ms", 15000)),
        intercept_refresh_s=int(c.get("intercept_refresh_s", 60)),
        intercept_scope=str(c.get("intercept_scope", "group")),
    )
```

- [ ] **Step 3: 在 `manifest.yaml` 的 `spec.config` 里声明同名配置项**

```yaml
spec:
  config:
    - name: bot
      type: bot-selector
      label: { zh_Hans: 使用的机器人 }
      required: true
    - name: bot_qq
      type: string
      label: { zh_Hans: 机器人 QQ 号（用于识别 @） }
      required: true
    - name: spring_base_url
      type: string
      label: { zh_Hans: Spring Boot 地址 }
      default: http://localhost:8080
    - name: dify_base_url
      type: string
      label: { zh_Hans: Dify API 地址（到 /v1） }
      default: http://localhost/v1
    - name: dify_api_key
      type: string
      label: { zh_Hans: Dify App API Key }
      required: true
    - name: group_prefix
      type: string
      label: { zh_Hans: 群聊触发前缀 }
      default: "!"
    - name: fact_auto_threshold
      type: float
      label: { zh_Hans: 事实自动写入置信度阈值 }
      default: 0.85
    - name: intercept_scope
      type: select
      label: { zh_Hans: 前置拦截范围 }
      default: group
      options: [ { label: 群聊, value: group }, { label: 全部, value: all }, { label: 关闭, value: off } ]
```

> 其余 delay/timeout 配置项照此格式补齐，名称必须与 `load_settings` 的 key 一字不差。

- [ ] **Step 4: `requirements.txt`**

```text
httpx>=0.27
```

- [ ] **Step 5: 冒烟**

在 LangBot 插件管理里加载插件，确认日志出现初始化成功、配置项可填写。

- [ ] **Step 6: Commit**

```bash
git add QQMessageAssistantPython/qq_assistant_plugin
git commit -m "feat(plugin): scaffold QQ assistant LangBot plugin with config"
```

---

## Task 2: 文本工具与拟人延迟

**Files:**
- Create: `core/text.py`
- Test: `tests/test_text.py`

**Interfaces:**
- Produces:
  - `extract_text(message_chain) -> str`
  - `has_file(message_chain) -> bool`
  - `is_at_bot(message_chain, bot_qq: int) -> bool`
  - `human_delay_seconds(text, settings) -> float`

- [ ] **Step 1: 写测试**

```python
from core.text import extract_text, human_delay_seconds, is_at_bot
from langbot_plugin.api.entities.builtin.platform.message import MessageChain, Plain, At

def test_extract_text_joins_plain_only():
    chain = MessageChain([Plain(text="在"), Plain(text="吗")])
    assert extract_text(chain) == "在吗"

def test_at_bot_detection():
    chain = MessageChain([At(target=12345), Plain(text=" 你好")])
    assert is_at_bot(chain, 12345) is True
    assert is_at_bot(chain, 99999) is False

def test_delay_formula_bounds():
    s = human_delay_seconds("x" * 200, settings)
    assert s == 15.0          # 上限
    assert 1.5 <= human_delay_seconds("嗯", settings) <= 2.5
```

> `settings` 用 Task 1 的 `Settings(...)` 直接构造一个测试实例。

- [ ] **Step 2: 跑测试确认失败**

```bash
cd QQMessageAssistantPython/qq_assistant_plugin
../.venv/Scripts/python.exe -m pip install pytest respx
../.venv/Scripts/python.exe -m pytest tests/test_text.py -q
```

预期：ImportError。

- [ ] **Step 3: 实现**

```python
import random
import time
from langbot_plugin.api.entities.builtin.platform import message as pm


def extract_text(chain) -> str:
    parts = []
    for comp in chain:
        if isinstance(comp, pm.Plain):
            parts.append(comp.text or "")
    return "".join(parts).strip()


def has_file(chain) -> bool:
    return any(isinstance(comp, pm.File) for comp in chain)


def is_at_bot(chain, bot_qq: int) -> bool:
    return any(isinstance(comp, pm.At) and str(comp.target) == str(bot_qq) for comp in chain)


def human_delay_seconds(text: str, settings) -> float:
    ms = (settings.delay_base_ms
          + len(text) * settings.delay_per_char_ms
          + random.randint(0, settings.delay_jitter_ms))
    return min(ms, settings.delay_max_ms) / 1000.0
```

- [ ] **Step 4: 跑测试**

```bash
../.venv/Scripts/python.exe -m pytest tests/test_text.py -q
```

预期：3 passed。

- [ ] **Step 5: Commit**

```bash
git add core/text.py tests/test_text.py
git commit -m "feat(plugin): text extraction, at-bot detection, human-like delay"
```

---

## Task 3: Spring Boot 与 Dify 客户端

**Files:**
- Create: `core/spring_client.py`
- Create: `core/dify_client.py`
- Test: `tests/test_spring_client.py`、`tests/test_dify_client.py`

**Interfaces:**
- Produces（Spring）：
  - `log_messages(items: list[dict]) -> int`
  - `list_rules() -> list[dict]`
  - `check_replied(chat_id, task_type, task_key) -> bool`
  - `mark_replied(chat_id, task_type, task_key) -> None`
  - `profile(sender_id) -> dict`（`{facts, stylePrompt}`）
  - `create_draft(type, sender_id, chat_id, title, payload) -> dict`
  - `create_memo(payload) -> dict`
  - `patch_memo(memo_id, body) -> dict`
  - `list_memos(sender_id, status) -> list[dict]`
  - `upsert_fact(sender_id, fact_key, fact_value, confidence) -> dict`
  - `sync_file_task(body) -> dict`
- Produces（Dify）：`chat(query, user, conversation_id=None, inputs=None) -> dict`（返回 `answer` / `conversation_id`）

- [ ] **Step 1: 写 `core/spring_client.py`**

```python
import logging
import httpx

log = logging.getLogger(__name__)


class SpringClient:
    def __init__(self, base_url: str, timeout: float = 5.0):
        self._client = httpx.AsyncClient(base_url=base_url, timeout=timeout)

    async def close(self):
        await self._client.aclose()

    async def _request(self, method: str, path: str, **kwargs):
        resp = await self._client.request(method, path, **kwargs)
        resp.raise_for_status()
        body = resp.json()
        if body.get("code") != 0:
            raise RuntimeError(f"Spring Boot {path}: {body.get('message')}")
        return body.get("data")

    async def log_messages(self, items):
        return await self._request("POST", "/api/message-logs", json=items)

    async def list_rules(self):
        return await self._request("GET", "/api/collection-tasks/rules")

    async def check_replied(self, chat_id, task_type, task_key) -> bool:
        data = await self._request("POST", "/api/collection-tasks/check",
                                   json={"chatId": chat_id, "taskType": task_type, "taskKey": task_key})
        return bool(data["replied"])

    async def mark_replied(self, chat_id, task_type, task_key):
        await self._request("POST", "/api/collection-tasks/mark",
                            json={"chatId": chat_id, "taskType": task_type, "taskKey": task_key})

    async def profile(self, sender_id):
        return await self._request("GET", f"/api/profile/{sender_id}")

    async def create_draft(self, type_, sender_id, chat_id, title, payload):
        return await self._request("POST", "/api/drafts", json={
            "type": type_, "senderId": sender_id, "chatId": chat_id,
            "title": title, "payload": payload,
        })

    async def create_memo(self, body):
        return await self._request("POST", "/api/memos", json=body)

    async def patch_memo(self, memo_id, body):
        return await self._request("PATCH", f"/api/memos/{memo_id}", json=body)

    async def list_memos(self, sender_id, status=None):
        params = {"senderId": sender_id}
        if status:
            params["status"] = status
        return await self._request("GET", "/api/memos", params=params)

    async def upsert_fact(self, sender_id, fact_key, fact_value, confidence):
        return await self._request("PUT", "/api/profile/facts", json={
            "senderId": sender_id, "factKey": fact_key,
            "factValue": fact_value, "confidence": confidence,
        })

    async def sync_file_task(self, body):
        return await self._request("POST", "/api/file-tasks/sync", json=body)
```

- [ ] **Step 2: 写 `core/dify_client.py`**

```python
import httpx


class DifyClient:
    def __init__(self, base_url: str, api_key: str, timeout: float = 60.0):
        self._client = httpx.AsyncClient(
            base_url=base_url,
            timeout=timeout,
            headers={"Authorization": f"Bearer {api_key}"},
        )

    async def close(self):
        await self._client.aclose()

    async def chat(self, query: str, user: str, conversation_id=None, inputs=None) -> dict:
        payload = {
            "inputs": inputs or {},
            "query": query,
            "response_mode": "blocking",
            "user": user,
        }
        if conversation_id:
            payload["conversation_id"] = conversation_id
        resp = await self._client.post("/chat-messages", json=payload)
        resp.raise_for_status()
        return resp.json()
```

- [ ] **Step 3: 用 respx 写两个客户端测试**

```python
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
```

- [ ] **Step 4: 跑测试**

```bash
../.venv/Scripts/python.exe -m pip install pytest-asyncio
../.venv/Scripts/python.exe -m pytest tests -q
```

预期：text 3 + client 2+ 全绿。

- [ ] **Step 5: Commit**

```bash
git add core/spring_client.py core/dify_client.py tests/test_spring_client.py tests/test_dify_client.py
git commit -m "feat(plugin): Spring Boot and Dify HTTP clients"
```

---

## Task 4: 异步消息日志队列

**Files:**
- Create: `core/logging_queue.py`

**Interfaces:**
- Produces: `MessageLogQueue(spring_client).enqueue(item: dict)`、`.start()`、`.stop()`

**规则：** 内存队列上限 1000；满则丢最旧并 `log.warning`；每 1 秒或攒够 20 条 flush 一次；flush 失败放回队首，最多重试 3 次；**绝不能抛到事件处理链**。

- [ ] **Step 1: 实现**

```python
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

    def enqueue(self, item: dict):
        if len(self._q) >= self._maxlen:
            self._q.popleft()
            log.warning("message log queue full, dropping oldest")
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
            batch = [self._q.popleft() for _ in range(min(self._batch, len(self._q)))]
            for attempt in range(3):
                try:
                    await self._spring.log_messages(batch)
                    break
                except Exception as e:
                    if attempt == 2:
                        log.error("failed to flush %d logs: %s", len(batch), e)
                    else:
                        await asyncio.sleep(0.5 * (attempt + 1))
```

- [ ] **Step 2: Commit**

```bash
git add core/logging_queue.py
git commit -m "feat(plugin): async batched message log queue"
```

---

## Task 5: 前置拦截

**Files:**
- Create: `core/rules.py`
- Test: `tests/test_rules.py`

**Interfaces:**
- Produces: `RuleCache(spring).refresh()`、`.match(text) -> dict | None`、`task_key(chat_id, rule_name, window) -> str`

**匹配规则：**
1. 规则按 `priority` 升序（后端已排序）。
2. `keywords` 用逗号分隔，**任一**命中即算命中；为空则跳过关键词检查。
3. `regex` 非空则必须 `re.search` 命中；正则非法 → 该条跳过并告警，**不能让一条坏规则打死所有拦截**。
4. 两者都为空 → 忽略该规则。
5. `keywords` 与 `regex` 同时存在时取 **AND**（更精确）；文档里要写清楚。

**taskKey 窗口：** 默认按天 `f"{chat_id}-{rule_name}-{date}"`；配置 `window: "week"` 时用 ISO 周。当前 `intercept_rule` 表没有窗口字段，先统一按天，Phase 5 再加配置。

- [ ] **Step 1: 写测试**

```python
from core.rules import matches

RULES = [
    {"name": "学号", "keywords": "学号,姓名", "regex": r"(学号|姓名)\s*[:：]", "enabled": True},
    {"name": "坏正则", "keywords": "坏", "regex": "([", "enabled": True},
]

def test_keyword_and_regex():
    assert matches("请填学号：2023001", RULES[0]) is True
    assert matches("今天天气不错", RULES[0]) is False

def test_bad_regex_does_not_raise():
    assert matches("坏消息", RULES[1]) is False
```

- [ ] **Step 2: 实现**

```python
import logging
import re
from datetime import date

log = logging.getLogger(__name__)


def matches(text: str, rule: dict) -> bool:
    if not text:
        return False
    keywords = [k.strip() for k in (rule.get("keywords") or "").split(",") if k.strip()]
    regex = (rule.get("regex") or "").strip()

    if keywords and not any(k in text for k in keywords):
        return False
    if regex:
        try:
            if not re.search(regex, text):
                return False
        except re.error as e:
            log.warning("invalid regex in rule %s: %s", rule.get("name"), e)
            return False
    return bool(keywords or regex)


def task_key(chat_id: int, rule_name: str, window: str = "day") -> str:
    today = date.today()
    suffix = f"{today.isocalendar().year}W{today.isocalendar().week}" if window == "week" else today.isoformat()
    return f"{chat_id}-{rule_name}-{suffix}"


class RuleCache:
    def __init__(self, spring):
        self._spring = spring
        self._rules = []

    async def refresh(self):
        try:
            self._rules = [r for r in (await self._spring.list_rules()) or [] if r.get("enabled", True)]
        except Exception as e:
            log.warning("refresh intercept rules failed, keep %d cached: %s", len(self._rules), e)

    def match(self, text: str):
        for rule in self._rules:
            if matches(text, rule):
                return rule
        return None
```

- [ ] **Step 3: 跑测试**

```bash
../.venv/Scripts/python.exe -m pytest tests/test_rules.py -q
```

预期：2 passed。

- [ ] **Step 4: Commit**

```bash
git add core/rules.py tests/test_rules.py
git commit -m "feat(plugin): intercept rule cache and matcher"
```

---

## Task 6: Dify 分发器

**Files:**
- Create: `core/dispatch.py`
- Test: `tests/test_dispatch.py`

**Interfaces:**
- Produces: `parse_answer(answer: str) -> dict`、`Dispatcher(spring, settings).handle(payload, ctx, sender_id, chat_id) -> str | None`（返回实际发出的文本，供日志用）

**实现要点：**

```python
import asyncio
import json
import logging
from core.text import human_delay_seconds

log = logging.getLogger(__name__)


def parse_answer(answer: str) -> dict:
    text = (answer or "").strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.startswith("json"):
            text = text[4:]
    try:
        data = json.loads(text)
        if isinstance(data, dict) and data.get("action"):
            return data
    except json.JSONDecodeError:
        pass
    return {"action": "SEND", "content": answer}


class Dispatcher:
    def __init__(self, spring, settings, reply):
        self._spring = spring
        self._settings = settings
        self._reply = reply          # async (ctx, text) -> None

    async def handle(self, data: dict, ctx, sender_id: int, chat_id: int | None) -> str | None:
        action = data.get("action", "SILENT")

        if action == "SILENT":
            return None

        if action == "SEND":
            return await self._send(ctx, data.get("content", ""))

        if action == "DRAFT":
            draft = data.get("draft") or {}
            await self._spring.create_draft(
                draft.get("type", "REPLY"), sender_id, chat_id,
                draft.get("title"), draft.get("payload") or {})
            return None

        if action == "MEMO":
            memo = data.get("memo") or {}
            await self._spring.create_memo({**memo, "senderId": sender_id, "chatId": chat_id})
            if data.get("content"):
                return await self._send(ctx, data["content"])
            return None

        if action == "FACT":
            fact = data.get("fact") or {}
            confidence = float(fact.get("confidence", 0))
            if confidence >= self._settings.fact_auto_threshold:
                await self._spring.upsert_fact(
                    sender_id, fact.get("factKey"), fact.get("factValue"), confidence)
            else:
                await self._spring.create_draft(
                    "FACT", sender_id, chat_id, "待确认事实", fact)
            return None

        if action == "FILE_TASK":
            # Phase 3-2 接管；本阶段先记日志
            log.info("file task delegated: %s", data.get("file"))
            return None

        log.warning("unknown action %s, fallback to SEND", action)
        return await self._send(ctx, data.get("content", ""))

    async def _send(self, ctx, content: str) -> str | None:
        if not content:
            return None
        await asyncio.sleep(human_delay_seconds(content, self._settings))
        await self._reply(ctx, content)
        return content
```

> `ctx.reply` 要求 `MessageChain`，统一由 Task 7 的 `reply_text(ctx, content)` 负责包装；本 Task 单测传入 fake reply 断言延迟与调用即可。

- [ ] **Step 1: 写 4 个测试**

1. `parse_answer` 正常 JSON → action 原样。
2. `parse_answer("```json {...} ```")` → 解析成功。
3. `parse_answer("随便说点什么")` → `{"action":"SEND","content":"随便说点什么"}`。
4. `FACT` 低置信 → 调 `create_draft("FACT", ...)` 而非 `upsert_fact`（用 AsyncMock 断言）。

- [ ] **Step 2: 跑测试并 Commit**

```bash
../.venv/Scripts/python.exe -m pytest tests/test_dispatch.py -q
git add core/dispatch.py tests/test_dispatch.py
git commit -m "feat(plugin): Dify structured action dispatcher with safe fallback"
```

---

## Task 7: 事件监听器与主流程

**Files:**
- Modify: `components/event_listener/default.py`
- Modify: `main.py`

**主流程（严格按此顺序）：**

```python
@self.handler(events.PersonMessageReceived)
async def on_person(ctx):
    await handle_message(ctx, is_group=False)

@self.handler(events.GroupMessageReceived)
async def on_group(ctx):
    await handle_message(ctx, is_group=True)


async def handle_message(ctx, is_group: bool):
    ctx.prevent_default()
    ctx.prevent_postorder()

    event = ctx.event
    chain = event.message_chain
    text = extract_text(chain)
    sender_id = int(event.sender_id)
    chat_id = int(event.launcher_id) if is_group else None
    bot_uuid = await ctx.get_bot_uuid()

    # 1. 记 IN 日志（不阻塞、不抛错）
    deps.log_queue.enqueue({
        "senderId": sender_id, "chatId": chat_id, "direction": "IN",
        "msgType": "file" if has_file(chain) else "text", "content": text,
    })

    # 2. 文件消息 → Phase 3-2
    if has_file(chain):
        asyncio.create_task(deps.file_handler.handle(ctx, event, is_group))
        return

    if not text:
        return

    # 3. 私聊「完成/取消」闭环
    if not is_group and closed_loop.classify(text):
        await closed_loop.handle(text, sender_id, ctx, reply_text, deps)
        return

    # 4. 目标判定（群聊只响应 @ 或前缀）
    if is_group:
        triggered = is_at_bot(chain, deps.settings.bot_qq) or text.startswith(deps.settings.group_prefix)
    else:
        triggered = True

    # 5. 前置拦截（按配置范围）
    rule = None
    if deps.settings.intercept_scope == "all" or (deps.settings.intercept_scope == "group" and is_group):
        rule = deps.rule_cache.match(text)

    if rule:
        key = task_key(chat_id, rule["name"])
        if not await deps.spring.check_replied(chat_id, rule["name"], key):
            await asyncio.sleep(human_delay_seconds(rule["replyTemplate"], deps.settings))
            await reply_text(ctx, rule["replyTemplate"])
            await deps.spring.mark_replied(chat_id, rule["name"], key)
            deps.log_queue.enqueue({... "direction": "OUT", "content": rule["replyTemplate"]})
        return

    if not triggered:
        return

    # 6. 拉画像 + 调 Dify
    query = text.removeprefix(deps.settings.group_prefix) if is_group else text
    try:
        profile = await deps.spring.profile(sender_id)
        inputs = {"facts": profile.get("facts", {}), "style_prompt": profile.get("stylePrompt", "")}
        result = await deps.dify.chat(query, user=str(sender_id), inputs=inputs)
        data = parse_answer(result.get("answer", ""))
        sent = await deps.dispatcher.handle(data, ctx, sender_id, chat_id)
        if sent:
            deps.log_queue.enqueue({... "direction": "OUT", "content": sent})
    except Exception as e:
        log.exception("handle message failed: %s", e)
        # 降级：不打扰用户，交给草稿中心后续排查
```

**`reply_text` 辅助：**

```python
async def reply_text(ctx, content: str):
    await ctx.reply(platform_message.MessageChain([platform_message.Plain(text=content)]))
```

**`main.py`：**

```python
class QQAssistant(BasePlugin):
    async def initialize(self):
        await super().initialize()
        self.settings = load_settings(self)
        self.spring = SpringClient(self.settings.spring_base_url, self.settings.spring_timeout)
        self.dify = DifyClient(self.settings.dify_base_url, self.settings.dify_api_key, self.settings.dify_timeout)
        self.log_queue = MessageLogQueue(self.spring)
        self.log_queue.start()
        self.rule_cache = RuleCache(self.spring)
        await self.rule_cache.refresh()
        self._rule_task = asyncio.create_task(self._refresh_rules_loop())

    async def _refresh_rules_loop(self):
        while True:
            await asyncio.sleep(self.settings.intercept_refresh_s)
            await self.rule_cache.refresh()

    async def terminate(self):
        self._rule_task.cancel()
        await self.log_queue.stop()
        await self.spring.close()
        await self.dify.close()
        await super().terminate()
```

- [ ] **Step 1: 按上面结构落地，先把 `file_handler` 留成空壳（Phase 3-2 实现）**
- [ ] **Step 2: 真机冒烟**

1. 私聊发「在吗」→ 收到 Dify 回复，且日志表出现 IN/OUT 两条。
2. 群里发普通消息 → 无回复，但日志表有 IN。
3. 群里 @机器人 / `!在吗` → 有回复。
4. 构造一条启用中的拦截规则，群里发关键词 → 回复模板；重复发 → 不再回复。

- [ ] **Step 3: Commit**

```bash
git add components/event_listener/default.py main.py
git commit -m "feat(plugin): main message pipeline with interception, Dify routing, logging"
```

---

## Task 8: 备忘 QQ 闭环

**Files:**
- Create: `core/closed_loop.py`
- Test: `tests/test_closed_loop.py`

**规则：**

```python
DONE_WORDS = {"完成", "已完成", "搞定了", "done"}
CANCEL_WORDS = {"取消", "不去了", "cancel"}

def classify(text: str) -> str | None:
    t = text.strip().lower()
    if t in DONE_WORDS: return "DONE"
    if t in CANCEL_WORDS: return "CANCELLED"
    return None
```

只认**整句精确匹配**（避免误伤「完成作业了吗」这类正常聊天）。若以后要放宽，必须加前缀限制（如 `#完成`）。

处理：

```python
async def handle(text, sender_id, ctx, reply_text, deps):
    status = classify(text)
    memo = None
    for s in ("AWAITING_REPLY", "REMINDED"):
        items = await deps.spring.list_memos(sender_id, s)
        if items:
            memo = max(items, key=lambda m: m["id"])
            break
    if memo is None:
        await reply_text(ctx, "没有找到待收尾的提醒～")
        return
    await deps.spring.patch_memo(memo["id"], {"status": status})
    await reply_text(ctx, f"已{'完成' if status == 'DONE' else '取消'}：{memo['title']}")
```

- [ ] **Step 1: 写 3 个测试**：完成词 → `DONE`；取消词 → `CANCELLED`；普通句子 → `None`。
- [ ] **Step 2: 真机冒烟**：库里手动插一条 `status=AWAITING_REPLY` 的 memo，私聊发「完成」，确认 `PATCH` 生效。
- [ ] **Step 3: Commit**

```bash
git add core/closed_loop.py tests/test_closed_loop.py
git commit -m "feat(plugin): QQ done/cancel closed loop for memos"
```

---

## 验收清单

- [ ] 私聊正常对话有拟人延迟（1.5s 起，长文本更久，上限 15s）
- [ ] 群聊只响应 @ / 前缀；所有消息都进 `message_log`
- [ ] 拦截命中且未回复 → 回复模板并 mark；重复消息不再发
- [ ] Dify 返回非法 JSON → 整段当文本发出，不丢消息
- [ ] 事实低置信走草稿，高置信直接写库
- [ ] 私聊「完成/取消」能正确落到最近一条 `AWAITING_REPLY` 备忘
- [ ] 规则热更新：Vue/curl 改规则后 ≤ 60s 生效，无需重启 LangBot
