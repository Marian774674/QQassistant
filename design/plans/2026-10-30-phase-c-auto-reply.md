# Phase C：F2 自动回复 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让机器人在「用户允许的会话」里用主人的人设说日常话；其余情况保持现状（只干活、不闲聊）。

**Architecture:** 判定拆成「做不做」和「说不说」。会话消息照旧送 Dify，Dify 照旧执行备忘/文件/草稿，
只在决策信封里多输出 `send_kind`（`TASK` / `CHAT`）与 `needs_confirm`。
插件按 Spring 给出的会话开关与限流快照决定这条 `CHAT` 发不发；`needs_confirm=true` 时降级成
`draft(type=REPLY)` 交给草稿中心。Spring 只多一个只读接口，不新增表。

**Tech Stack:** Spring Boot 3.4.12 / Java 21 / MyBatis-Plus；LangBot 插件（Python 3.12，httpx）；
Dify Chatflow（DSL 0.1.5）；Vue3 + TS + Vite。

**Spec:** `design/core_features.md` §8（F2 自动回复）

## Global Constraints

- **红线**：业务逻辑不进 Dify 提示词；Spring 不直连 NapCat；插件是唯一 QQ 出口。
- **不自动提交 git**（用户明令）。
- 改字段的顺序**永远是**：`design/agent_contract.md` → 各端代码。
- 时间一律由 Java 写 `LocalDateTime.now()`，不依赖库默认值（那台 MySQL 跑 UTC）。
- Dify 的 Start 变量只认**预定义**的名字，插件发未声明变量 → 400。信封缺 `send_kind` 按 `TASK` 处理
  （宁可多发一条，不能静默丢消息）。
- 前端无 vitest；验证只有 `npm run build`（vue-tsc 类型检查 + 构建）。
- Python 用 `.venv/Scripts/python.exe`，测试必须在 `qq_assistant_plugin` 目录下跑。

## Review Focus

1. **开关关闭时仍要能干活**：`auto_reply_enabled=0` 的群里 @机器人 建备忘必须照常工作，
   只有闲聊应答被吞掉——拿开关去卡 Dify 调用就是功能倒退。
2. **限流只掐 CHAT**：同一轮里 `TASK` 的确认语（改好了：…）不能被限流连带吞掉。
3. **草稿不能凭空丢**：`needs_confirm` 建草稿失败时，宁可不发也不能发出去且不留痕；
   反过来草稿建成功但用户不点，用户就永远等不到回复——两端都要能查（`file_store.debug` + 草稿中心）。
4. **recent_messages 不能是空的历史**：Spring 取不到会话时返回 `[]`，Dify 必须仍能正常工作
   （不许让空数组变成 500 或让模型从 `null` 里读）。
5. **旧插件 / 旧 Dify 组合**：新插件 + 旧 Chatflow（无 `send_kind`）要跟今天完全一致；
   新 Chatflow + 旧插件（不认这个字段）也不能崩。

---

### Task 1: 契约与配置项

**Files:**
- Modify: `design/agent_contract.md` §1.1、§2.1
- Modify: `design/api_contract.md`（新增 14.6 节）
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/impl/AppSettingServiceImpl.java`（默认值，若其中有默认值表）

**Interfaces:**
- Produces: Start 变量名 `reply_prompt` / `recent_messages` / `reply_rules`；信封字段 `send_kind` / `needs_confirm`；
  接口 `GET /api/chat-settings/reply-context` 的形状。Task 2–5 全部按这里写。

- [ ] **Step 1: 编辑 `design/agent_contract.md` §1.1**，在 `event_id` 后加三行：

| 变量 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `reply_prompt` | text | 否 | 主人写的额外要求（`app_setting.reply_prompt`）；空串=没有 |
| `recent_messages` | text | 否 | 本会话最近 N 条消息的 JSON 数组，按时间正序；取不到发 `[]` |
| `reply_rules` | text | 否 | 固定约束的机器可读版，优先级高于 `reply_prompt` |

并在 §1.1 后补一段 `recent_messages` 的元素形状：

```json
[{"senderName":"阿黄","direction":"IN","content":"明天几点集合","createdAt":"2026-10-30T21:14:00"}]
```

- [ ] **Step 2: 编辑 §2.1 通用信封**，加两个字段（都带缺省语义）：

```json
{"version":1,"action":"SEND","send_kind":"TASK | CHAT","needs_confirm":false,"content":"…"}
```

- `send_kind`：`TASK` = 干活/反问的结果，**总是发**；`CHAT` = 日常对话应答，受会话开关与限流约束。
  **缺省（字段不存在）按 `TASK`**。
- `needs_confirm`：仅对 `CHAT` 有意义。`true` → 插件不发，写 `draft(type=REPLY)`。
  缺省 / 非 `true` → 直接发。

- [ ] **Step 3: 在 `design/api_contract.md` 的「十四」之后加 14.6 小节**，写死响应：

```json
{"code":0,"data":{
  "enabled": true,
  "replyPrompt": "我是张三，说话随意一点",
  "rateLimit": 20,
  "outLastHour": 3,
  "recentMessages": [
    {"senderName":"阿黄","direction":"IN","content":"明天几点集合","createdAt":"2026-10-30T21:14:00"}
  ]
}}
```

- [ ] **Step 4: 记录裁决**：本计划用一个 `reply-context` 代替 `core_features.md` §8.2 写的
  `GET /api/message-logs/recent`，理由与代价写进 ledger。

- [ ] **Step 5: Commit**（**不执行**——用户明令不自动 git）

---

### Task 2: Spring `GET /api/chat-settings/reply-context`

**Files:**
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/vo/ReplyContextVO.java`
- Modify: `.../service/ChatSettingService.java`、`.../service/impl/ChatSettingServiceImpl.java`
- Modify: `.../controller/ChatSettingController.java`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/service/ReplyContextTest.java`

**Interfaces:**
- Consumes: Task 1 的响应形状。
- Produces: `ReplyContextVO getReplyContext(Long chatId, String kind, int limit)`；
  `ReplyContextVO{Boolean enabled; String replyPrompt; Integer rateLimit; Long outLastHour; List<RecentMessageVO> recentMessages}`；
  `RecentMessageVO{String senderName; String direction; String content; LocalDateTime createdAt}`。

- [ ] **Step 1: 写失败测试** `ReplyContextTest`（`@SpringBootTest @ActiveProfiles("test") @Transactional`）：

```java
@Test
void replyContext_reportsOffWhenNoSettingRowExists()   // 没建 chat_setting → enabled=false，recentMessages 仍返回
@Test
void replyContext_readsTheSwitchAndThePrompt()         // upsert(autoReply=true) + app_setting.reply_prompt → 两值对上
@Test
void replyContext_countsOnlyChatRepliesInTheLastHour() // 3 条 OUT/CHAT + 1 条 OUT/TASK + 1 条更早的 → outLastHour=3
@Test
void replyContext_returnsRecentMessagesInChronologicalOrder() // 插 3 条乱序 createdAt → 返回正序、条数受 limit 限制
@Test
void replyContext_ratesLimitDefaultsToTwenty()         // 没有 app_setting.reply_rate_limit → rateLimit=20
```

- [ ] **Step 2: 运行**：`mvn test -Dtest=ReplyContextTest`，Expected: FAIL（编译错/方法不存在）

- [ ] **Step 3: 实现**。要点：
  - `enabled`：查 `chat_setting(chat_id, kind)`，无行 = false，不抛错。
  - `replyPrompt`：`appSettingService.getOrDefault("reply_prompt","")`。
  - `rateLimit`：`app_setting.reply_rate_limit`，解析失败或 ≤0 → 20。
  - `outLastHour`：`message_log` 里 `chat_id=? AND direction='OUT' AND reply_kind='CHAT' AND created_at > now-1h` 的 `COUNT`。
    私聊时 `chat_id` 传的是对方号码，也要能查到（插件用同一个值）。
  - `recentMessages`：同 `chat_id`，不限方向，按 `created_at DESC, id DESC` 取 limit 条后**反转为正序**；
    `content` 压掉换行并截 200 字。
  - Controller：`@GetMapping("/reply-context")`，参数 `chatId`（必填）、`kind`（默认 `GROUP`，校验 ∈ {GROUP,PRIVATE}）、
    `limit`（默认 15，clamp 1–50）。

- [ ] **Step 4: 由红转绿**：`mvn test -Dtest=ReplyContextTest`，Expected: PASS 5/5

- [ ] **Step 5: 全量回归**：`mvn test`，Expected: 全绿

---

### Task 3: 插件输入 `reply_prompt` / `recent_messages` / `reply_rules`

**Files:**
- Create: `QQMessageAssistantPython/qq_assistant_plugin/core/reply_rules.py`
- Modify: `.../core/spring_client.py`（`reply_context`）
- Modify: `.../core/event.py`（`build_inputs` 三个新键）
- Modify: `.../components/event_listener/default.py`（拉取 + 透传）
- Test: `.../tests/test_event_inputs.py`、`.../tests/test_reply_context.py`

**Interfaces:**
- Consumes: `GET /api/chat-settings/reply-context`（Task 2）。
- Produces: `build_inputs(..., reply_prompt="", recent_messages=None, reply_rules="")`；
  `SpringClient.reply_context(chat_id, kind, limit=15) -> dict`；`core.reply_rules.RULES`（模块级字符串常量）。

- [ ] **Step 1: 写失败测试**：

```python
# tests/test_event_inputs.py
def test_build_inputs_carries_the_three_reply_keys():
    inputs = build_inputs(None, "text", "group", 123, 456,
                          reply_prompt="随意点", recent_messages=[{"content": "在吗"}],
                          reply_rules="不透露是机器人")
    assert inputs["reply_prompt"] == "随意点"
    assert json.loads(inputs["recent_messages"]) == [{"content": "在吗"}]
    assert inputs["reply_rules"] == "不透露是机器人"

def test_build_inputs_defaults_to_empty_strings():
    inputs = build_inputs(None, "text", "private", None, 456)
    assert inputs["recent_messages"] == "[]"
    assert inputs["reply_prompt"] == ""
    assert inputs["reply_rules"] == ""      # 空串由调用方决定发什么；默认不发规则

# tests/test_reply_context.py
@pytest.mark.asyncio
async def test_reply_context_returns_empty_snapshot_on_failure(...):
    # SpringClient._request 抛错时，default.py 的封装必须退化成默认快照，不能中断整轮
```

- [ ] **Step 2: 运行**：`../.venv/Scripts/python.exe -m pytest tests/test_event_inputs.py tests/test_reply_context.py -q`，Expected: FAIL

- [ ] **Step 3: 实现**：
  - `core/reply_rules.py`：一条常量字符串，内容是固定约束（不透露是机器人 / 不替主人承诺时间与金额 /
    不编造事实 / 不评价第三方 / 主人被点名要求答复时先降级为草稿）。**优先级高于 `reply_prompt`**，
    这句话本身也写进常量里。
  - `SpringClient.reply_context(chat_id, kind, limit=15)`：`GET /api/chat-settings/reply-context`。
  - `build_inputs` 三个新键，`recent_messages` 走 `json.dumps(ensure_ascii=False)`，取不到给 `"[]"`。
  - `default.py`：在 `open_memos` 之后用**同样 try/except** 的方式拉 `reply_context`，
    失败退化成 `{"enabled": False, "replyPrompt": "", "rateLimit": 20, "outLastHour": 0, "recentMessages": []}`
    ——失败时宁可闭嘴（不自动回复），也不能让整轮挂掉。

- [ ] **Step 4: 转绿**：同一命令，Expected: PASS

---

### Task 4: 插件出口门（开关 / 限流 / 草稿降级 / reply_kind）

**Files:**
- Modify: `.../core/dispatch.py`（`Dispatcher.handle` 增加 `reply_gate` 参数或提前改写 `data`）
- Modify: `.../components/event_listener/default.py`（`_respond` 里在 `parse_answer` 之后做门控）
- Test: `.../tests/test_reply_gate.py`

**Interfaces:**
- Consumes: Task 3 的 `reply_ctx` 快照；信封字段 `send_kind` / `needs_confirm`。
- Produces: `core.reply_gate.decide(data, reply_ctx) -> dict`（纯函数，返回**新的** data 与一个 `action` 提示），
  便于单测；`default.py` 只负责调用它。

- [ ] **Step 1: 写失败测试** `tests/test_reply_gate.py`（纯函数，无 mock）：

```python
def test_task_kind_is_always_sent_even_when_the_switch_is_off()
def test_chat_kind_is_suppressed_when_the_switch_is_off()      # content 变空，action 保留
def test_chat_kind_is_suppressed_when_over_the_rate_limit()
def test_task_kind_is_not_affected_by_the_rate_limit()
def test_needs_confirm_turns_a_chat_reply_into_a_reply_draft()  # 返回 draft 载荷 {type:REPLY, ...}
def test_missing_send_kind_defaults_to_task()                   # 旧 Dify 不崩、不静默丢
def test_unknown_send_kind_defaults_to_task()
```

- [ ] **Step 2: 运行**：`pytest tests/test_reply_gate.py -q`，Expected: FAIL

- [ ] **Step 3: 实现** `core/reply_gate.py`：

```python
def decide(data: dict, reply_ctx: dict) -> tuple[dict, str | None]:
    """返回 (改写后的 data, 要不要写 REPLY 草稿的动作说明)。

    - send_kind 缺省/非法 → TASK（宁可多发，不可静默丢）
    - CHAT + 开关关 → content 清空，其余字段保留（副作用照跑）
    - CHAT + 超限   → content 清空
    - CHAT + needs_confirm → content 清空，返回草稿说明
    """
```

`default.py` 在 `parse_answer` 之后调用；草稿说明非空时走 `deps.spring.create_draft("REPLY", sender_id, chat_id, title, payload)`，
`payload = {"content": ..., "chatId": ..., "senderId": ...}`，并 `file_store.debug` 记一行。

- [ ] **Step 4: OUT 日志带上 `replyKind`**：`_respond` 里成功发送时
  `deps.log_queue.enqueue({..., "replyKind": send_kind})`；拦截回复（`_intercept`）固定 `"TASK"`。
  加一条测试：门控抑制后**不产生** OUT 日志。

- [ ] **Step 5: 运行全量**：`pytest tests -q`，Expected: 全绿

---

### Task 5: Dify Chatflow（Start 变量、信封透传、提示词）

**Files:**
- Modify: `design/QQAssistant.chatflow.yml`
- Test: `QQMessageAssistantPython/qq_assistant_plugin/tests/test_chatflow_dsl.py`（新建）

**Interfaces:**
- Consumes: Task 1 的变量名与字段名。
- Produces: 一个能被 `yaml.safe_load` 的 Chatflow，Start 声明 18 个变量（原 15 + 3），
  `CLEAN_DECISION` 原样透传 `send_kind` / `needs_confirm`。

- [ ] **Step 1: 写失败测试** `tests/test_chatflow_dsl.py`：解析 `design/QQAssistant.chatflow.yml`，
  断言 Start 变量集合包含 `reply_prompt` / `recent_messages` / `reply_rules`；
  取出 `CLEAN_DECISION` 的代码并 `exec`，喂
  `{"version":1,"action":"SEND","send_kind":"CHAT","needs_confirm":true,"content":"好呀"}`，
  断言输出里两个字段保留；喂 `send_kind:"WEIRD"` 断言归一成 `TASK`；喂无关字段断言被丢弃。

- [ ] **Step 2: 运行**：Expected: FAIL

- [ ] **Step 3: 改 DSL**：
  - Start 节点加 3 个 text 变量（`recent_messages` max_length 20000）。
  - `CLEAN_DECISION` 增加归一逻辑：`send_kind` ∉ {TASK, CHAT} → `TASK`；`needs_confirm` 只保留布尔 `true`。
  - `CHAT_DECISION` 提示词加三块：①`send_kind` / `needs_confirm` 的判定规则（含
    「替主人承诺、定时间、谈钱、评价第三方、主人被点名要求答复」→ `needs_confirm=true`）；
    ②`recent_messages` 的用法（只能据此接话，不许编造它没说过的事）；
    ③`reply_prompt` 与 `reply_rules` 的优先级（冲突时以 `reply_rules` 为准）。

- [ ] **Step 4: 转绿**：`pytest tests/test_chatflow_dsl.py -q`，Expected: PASS

---

### Task 6: Vue 设置页

**Files:**
- Modify: `QQMessageAssistantVue/src/views/SettingsView.vue`（子提示词多行 + 限流数字）
- Modify: `QQMessageAssistantVue/src/views/ChatSettingsView.vue`（确认每个群的自动回复开关可用，标签写清「只影响闲聊」）
- Modify: `QQMessageAssistantVue/src/api/settings.ts`（若需类型）

**Interfaces:**
- Consumes: `PUT /api/settings`（`reply_prompt`、`reply_rate_limit`）。
- Produces: 用户能改子提示词与限流；群页能按开关自动回复。

- [ ] **Step 1: 设置页**：加「自动回复」分区：多行 `textarea`（`reply_prompt`，带 3 个占位示例：
  语气随意 / 别提我工作 / 被人 @ 才回）、数字输入 `reply_rate_limit`（1–200，默认 20）、保存按钮沿用现有 toast。
- [ ] **Step 2: 群设置页**：自动回复开关的说明文案改成「开启后才会替你说日常话；备忘、查资料照常工作」。
- [ ] **Step 3: 验证**：`npm run build`，Expected: 零错误（**说明：无自动化测试，仅类型检查与构建**）。

---

### Task 7: 文档收敛与全量验证

**Files:**
- Modify: `design/core_features.md`（§10 标记 5 完成）
- Modify: `design/dify_chatflow.md`（`CLEAN_DECISION` 与提示词段落同步）
- Modify: `README.md`（核心功能章节）

- [ ] **Step 1:** 三套全绿：`mvn test`、`pytest tests`、`npm run build`
- [ ] **Step 2:** 文档同步，**不写「已验证」**——Dify 需要人工重新导入发布，真机验证由用户完成。
- [ ] **Step 3:** ledger 记 `Ruling:` 与 `Final:`
