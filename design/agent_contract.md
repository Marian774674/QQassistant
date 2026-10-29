# Dify Agent 契约（事件输入 / 决策输出）

> 本文件是 **Dify Chatflow ↔ LangBot 插件 ↔ Spring Boot** 的唯一接口准据。
> 插件按本契约发送事件、校验决策；Dify 按本契约输出 JSON，并通过 HTTP 节点直连
> Spring 的窄写接口（见 §3）。任何一侧改字段，先改本文件，再改代码。

## 0. 职责边界

| 能力 | 归属 | 说明 |
|---|---|---|
| 意图识别、对话状态、是否等待文件、填什么 | **Dify** | 语义与决策 |
| 决定「改哪条备忘、改什么」（选 op + 填参数） | **Dify** | 语义判断，见 §3 |
| 写操作的校验、归属、状态机、提醒重算、审计、幂等 | **Spring Boot** | 业务规则唯一执行者 |
| QQ 收发、文件下载/结构化/写回/回传 | **LangBot 插件** | 二进制与传输 |
| 会话映射、文件任务、草稿、备忘、事实、消息日志 | **Spring Boot** | 持久化真相源 |
| QQ 协议 | **NapCat** | 只被 LangBot 调用 |
| 人工确认、配置 | **Vue** | 不参与实时决策 |

Dify 的会话变量只是**对话内的快捷状态**，不是数据库。插件重启、Dify 换应用时，
待办文件任务必须能从 Spring / 本地 `task.json` 恢复。

---

## 1. 事件输入（插件 → Dify `/chat-messages`）

每次调用都带 `conversation_id`（首次为空），`auto_generate_name=false`。

### 1.1 Start 变量

Chatflow 的 Start 节点必须**预先定义**下列变量；插件发送未声明的变量会被 Dify 判 400。
插件每轮都发全部 15 个键（缺值发空串），因此 Start 必须与下表一一对应。

| 变量 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `event_type` | text | 是 | `text` / `file` |
| `query` | text | 是 | 用户文本；文件事件为 `[文件] 文件名` 或随文件一起发的文本 |
| `facts` | text | 否 | 画像事实 JSON 字符串，如 `{"姓名":"张三"}` |
| `style_prompt` | text | 否 | 风格画像提示词 |
| `chat_type` | text | 是 | `private` / `group` |
| `chat_id` | text | 否 | 群号；私聊为空字符串 |
| `sender_id` | text | 是 | QQ 号 |
| `current_time` | text | 是 | ISO-8601 本地时间，如 `2026-10-26T15:36:00` |
| `file_task_ref` | text | 否 | 文件事件的任务引用 `ft_xxxxxxxx` |
| `file_name` | text | 否 | 原始文件名 |
| `file_kind` | text | 否 | `xlsx` / `docx` / `pdf` / `other` |
| `structure` | text | 否 | 文件结构 JSON 字符串（见 1.3），仅在文件相关轮次发送 |
| `pending_request_hint` | text | 否 | 插件/Spring 备份的待填要求；Dify 变量丢失时用于恢复 |
| `last_task_ref` | text | 否 | 上一轮文件任务引用，便于“把第二行也填上” |
| `open_memos` | text | 否 | 未终结备忘的紧凑 JSON 数组（见 1.4）；Dify 只能从这里取 `memoId` |
| `event_id` | text | 否 | 写操作的幂等键；插件在调 Dify 前生成，重试复用 |

`open_memos` / `event_id` 取不到时发 `[]` / `""`——**宁可让模型反问一句，也不能让它猜**。
`event_id` 为空时 Dify 侧不发起写操作（`CLEAN_DECISION` 直接回一句“再说一遍”）。

### 1.2 会话键

插件按 `(bot_uuid, chat_type, chat_id, sender_id)` 维护 `conversation_id`：

- 私聊：`chat_type=private`，`chat_id=0`；
- 群聊：`chat_type=group`，`chat_id=群号`，**默认按成员隔离**。

Dify 请求的 `user` 字段：

- 私聊：`private:{sender_id}`
- 群聊：`group:{chat_id}:{sender_id}`

### 1.3 `structure` 的格式

由插件从文件机械抽取，**只发结构，不发二进制**。大文件要 compact。

xlsx：

```json
{
  "kind": "xlsx",
  "sheets": [
    {
      "name": "Sheet1",
      "maxRow": 20,
      "maxCol": 6,
      "rows": [
        ["姓名", "学号", "专业"],
        ["", "", ""]
      ]
    }
  ]
}
```

docx：

```json
{
  "kind": "docx",
  "paragraphs": [{"index": 3, "text": "姓名：____"}],
  "tables": [
    {"table": 0, "rows": [["姓名", ""], ["学号", ""]]}
  ]
}
```

pdf：

```json
{"kind": "pdf", "text": "前 N 页抽取文本（截断）"}
```

上限建议：单次 `structure` ≤ 30KB；超限时只报表头、前 30 行、`maxRow/maxCol`。

### 1.4 `open_memos` 的格式

来自 `GET /api/memos/open?senderId=&limit=20`，插件 `json.dumps(ensure_ascii=False)` 后整包发送：

```json
[{"id":12,"title":"班会","content":"明天下午2点 教学楼S109",
  "dueTime":"2026-10-30T14:00","status":"ACTIVE","importance":3,"category":"学业"}]
```

- 只含未终结状态（`ACTIVE` / `REMINDED` / `AWAITING_REPLY` / `OVERDUE`），已完成/已取消的不出现
  ——否则模型会把已经关掉的备忘再改一遍。
- 排序：有 `dueTime` 的按时间升序在前，没有的按 `updated_at` 倒序在后；最多 20 条。
- `content` 截 60 字（token 与“选错 id”之间的折中）。

**这是模型唯一被允许取 `memoId` 的地方**：服务端会再校验归属，编造的 id 一律“备忘录不存在”。

---

## 2. 决策输出（Dify → 插件）

Chatflow 的 **Answer 节点只输出纯 JSON 文本**，不要 Markdown 代码块、不要解释文字。
插件会剥离 ``` 围栏后再解析；解析失败**不会**把原文发给用户。

### 2.1 通用信封

```json
{
  "version": 1,
  "action": "SEND | DRAFT | SILENT | MEMO_OP | MEMO | FACT | MEMO_CLOSE | WAIT_FILE | ASK | FILE_FILL",
  "content": "给用户看的文字，可为空",
  "draft": {},
  "memo": {},
  "fact": {},
  "wait": {},
  "ask": {},
  "file": {},
  "close": {}
}
```

### 2.2 各动作

#### SEND

```json
{"version":1,"action":"SEND","content":"好的，明天下午三点提醒你。"}
```

插件：私聊拟人延迟后发送；群聊直接发送。`content` 为空视为 SILENT。

#### DRAFT

```json
{"version":1,"action":"DRAFT","content":"",
 "draft":{"type":"REPLY","title":"给导师的回复","payload":{"content":"老师您好……"}}}
```

插件：写 Spring 草稿中心，不发 QQ。`type ∈ {REPLY, FILE, MEMO, FACT}`。

#### SILENT

```json
{"version":1,"action":"SILENT"}
```

插件：不产生任何用户可见输出。

#### MEMO_OP（当前路径）

备忘的新建 / 修改 / 完成 / 取消不再走信封，而是由 Dify 在 Chatflow 内部用 HTTP 节点
直接调 Spring（见 §3），再把服务端返回的确认语包成一条 `SEND` 交给插件。因此插件**看不到**
`MEMO_OP`：它只会收到形如 `{"version":1,"action":"SEND","content":"改好了：班会 · 10-31 16:00"}`
的普通回复。

`MEMO_OP` 只存在于两处：LLM 的输出（`CHAT_DECISION`）和 `CLEAN_DECISION` 的解析。
LLM 侧格式：

```json
{"version":1,"action":"MEMO_OP","op":"memo.update","memoId":"12",
 "content":"明天下午2点 教学楼 S111","importance":"","category":"",
 "dueTime":"","clearDueTime":"","status":""}
```

- `op ∈ {memo.create, memo.update, memo.close}`；没给的字段留空或省略。
- `senderId` / `chatId` / `eventId` **不在 LLM 输出里**：由 `CLEAN_DECISION` 从 Start 变量注入。
  模型可以决定“做什么”，但不能决定“替谁做”，也不能决定幂等键。
- 拿不准指向哪一条时，模型必须输出 `SEND` 反问，不许猜 `memoId`，也不许退化成新建。

#### MEMO（回滚路径，勿在新提示词中使用）

```json
{"version":1,"action":"MEMO","content":"记下了。",
 "memo":{"title":"交实验报告","content":"周五前交","importance":3,
         "category":"学业","dueTime":"2026-10-30T18:00:00"}}
```

插件：建备忘；`content` 可选，作为确认语。**同样的问题它会犯**：永远新建，不认旧备忘。
保留它只为回滚：把提示词退回旧文本、把 MEMO_OP 分支接走即可，无需改插件。

#### FACT

```json
{"version":1,"action":"FACT",
 "fact":{"factKey":"学号","factValue":"2023001","confidence":0.95}}
```

插件：`confidence >= fact_auto_threshold` 自动写事实；否则建 FACT 草稿。

#### MEMO_CLOSE（回滚路径，勿在新提示词中使用）

```json
{"version":1,"action":"MEMO_CLOSE","content":"这件事已完成。",
 "close":{"memo_id":123,"status":"DONE"}}
```

插件：按 `memo_id` 更新；为空时查该用户最新 `AWAITING_REPLY` 备忘。
`status ∈ {DONE, CANCELLED}`。已被 MEMO_OP 的 `memo.close` 取代。

#### WAIT_FILE

用户先提要求、还没发文件时：

```json
{"version":1,"action":"WAIT_FILE","content":"发过来吧，我按你说的填。",
 "wait":{"request":"把姓名和学号填上","ttl_seconds":900}}
```

插件：发送 `content`；把 `wait.request` 镜像到 `file_task`/Spring 作为备份。
真正的 pending 状态由 Dify 会话变量保存。

#### ASK

缺字段、或文件先到时追问：

```json
{"version":1,"action":"ASK","content":"还差学号，补一下。",
 "ask":{"missing":["学号"],"task_ref":"ft_ab12cd34"}}
```

插件：发送 `content`；保持文件任务为 `NEED_INPUT`，把 `task_ref` 记为当前活跃任务。

#### FILE_FILL

```json
{"version":1,"action":"FILE_FILL","content":"填好了。",
 "file":{"task_ref":"ft_ab12cd34",
         "fills":[{"sheet":"Sheet1","ref":"B2","value":"张三"}],
         "missing":[],"confidence":0.95,"needs_confirm":false}}
```

插件：按 `task_ref` 找文件 → 执行 `fills` → 回传或建 FILE 草稿。
`fills` 的格式见 `plans/2026-10-18-phase3-2-file-tasks.md` Task 3。

---

## 3. 写操作（Dify → Spring `POST /api/agent/ops`）

Chatflow 里 `IS_OP` → `OP_HTTP` 这一个节点承担备忘的新建/修改/完成/取消。
**Dify 只填参数，不实现规则**：校验、归属、状态机、提醒重算、审计、幂等全部在 Spring。

### 3.1 请求

请求头 `X-Agent-Token: {{#env.AGENT_OPS_TOKEN#}}`；body **全部字段为字符串**（空串 = 未提供），
由 `CLEAN_DECISION` 用 `json.dumps` 整包序列化（Dify 的 JSON body 只允许一个条目）：

```json
{"eventId":"8f1c…","op":"memo.update","senderId":"1753800374","chatId":"123456",
 "memoId":"12","title":"","content":"明天下午2点 教学楼 S111","importance":"",
 "category":"","dueTime":"","clearDueTime":"","status":""}
```

| 字段 | 说明 |
|---|---|
| `eventId` | **必填**，幂等键，≤64 字 |
| `op` | `memo.create` / `memo.update` / `memo.close` |
| `senderId` | 由插件→Start→代码节点注入，模型无权填写 |
| `memoId` | 只能取自 `open_memos` |
| `clearDueTime` | 只有 `"true"` 才清空时间（`dueTime` 传空串表示“没提供”，不是“清空”） |
| `status` | 仅 `memo.close` 使用：`DONE` / `CANCELLED`；`memo.update` 带 status 会被拒 |

### 3.2 响应与错误语义

```json
{"code":0,"message":"改好了：班会 · 10-31 16:00 · 教学楼 S111",
 "data":{"op":"memo.update","memoId":12,"status":"ACTIVE","replayed":false,
         "reminders":[{"offsetLabel":"2h","remindAt":"2026-10-31T14:00:00"}]}}
```

| 情况 | HTTP | `code` | Dify 侧行为 |
|---|---|---|---|
| 成功 | 200 | 0 | `READ_OP_RESULT` 把 `message` 原样转达 |
| 业务拒绝（校验/归属/找不到/重复参数） | **200** | **400** | `READ_OP_RESULT` 把中文 `message` 原样转达 |
| token 缺失/错误/未配置 | 401 / 503 | 同值 | 走 `OP_FAILED` 的通用道歉 |
| 后端不可用、超时、非 JSON | 非 2xx | — | 走 `OP_FAILED` 的通用道歉 |

**业务拒绝必须是 200 + `code!=0`**：否则“备忘录不存在”会被说成“后台没连上”，用户会以为重试就能好。

`message` 由 **Spring 生成**（“记下了：… / 改好了：… / 已完成：… / 已取消：…”），不交给模型编。

### 3.3 幂等

`eventId` + `memo_change_log.uk_event` 唯一索引：同一事务内先 `INSERT IGNORE` 占位，
影响行数为 0 说明是重放 → 直接返回库里存的 `result_json`（`replayed:true`），业务写入不再执行。

因此**插件必须在调 Dify 之前生成 `eventId`，并在“会话丢失重试”时复用同一个值**；
Dify 侧的 `retry_config` 也必须关掉，直到去重路径经过实测。

### 3.4 不做什么

- 不做 `fact.upsert`（会绕过 `fact_auto_threshold` 与 FACT 草稿降级）。
- 不做“取消后恢复成 ACTIVE”（要改 `PATCH_STATUSES` 与提醒重建语义）。
- 不让 Dify 直接写表、不把状态迁移规则写进提示词。

---

## 4. 校验与降级（插件侧）

| 情况 | 插件行为 |
|---|---|
| Answer 不是 JSON / 缺 `action` | 记日志，**不发送原文**；降级 SILENT |
| 未知 `action` | 记日志，降级 SILENT（不再 fallback 成 SEND） |
| `SEND`/`DRAFT` 缺必填字段 | 降级 SILENT + 日志 |
| `FILE_FILL` 缺 `task_ref` 或 `fills` 为空 | 不写文件；降级 ASK 或日志 |
| `FILE_FILL` 置信度低于 `file_confirm_threshold` / `needs_confirm=true` | 建 FILE 草稿，不发 QQ |
| 写文件报错 | 建 FILE 草稿（保留原文件与错误信息），不发半成品 |
| Dify HTTP 非 2xx | 记日志；清理失效 `conversation_id` 后重试一次；仍失败则不回复 |
| `conversation_id` 失效（`Conversation Not Exists`） | reset 映射 → 新会话重试一次 |

**红线：任何情况下都不能把 LLM 的原始 JSON/异常文本发给用户。**

---

## 5. 状态归属

| 状态 | 主存储 | 备份 | 生命周期 |
|---|---|---|---|
| `conversation_id` | Spring `conversation_session` | 插件本地 `_conversations.json` | 7 天 TTL / 用户“重新开始” |
| `pending_file_request` | Dify conversation variable | Spring `file_task.pending_request` | `ttl_seconds`（默认 900s） |
| `pending_file_task_ref` / `awaiting` | Dify conversation variable | 插件 `_active/<sender>.txt` + Spring `file_task` | 任务结束或过期 |
| 文件任务全文 | 插件 `task.json` | Spring `file_task.detail` | 任务保留至手动清理 |
| 草稿/备忘/事实/消息日志 | Spring | — | 业务生命周期 |

---

## 6. 错误码与日志字段

插件日志（`file_store.debug`）至少包含：

```
event=<text|file> sender=<id> chat=<id|-> conv=<id|-> task=<ft_xxx|-> ev=<event_id 前 12 位> action=<ACTION> result=<ok|fallback|error>
```

Dify 返回的错误体保留前 500 字符；QQ 签名 URL 必须脱敏（只留 host + 状态码）。

---

## 7. 版本策略

- `version` 当前为 `1`。新增字段向后兼容；删除/改义字段必须升 `version`。
- 插件遇到更高 `version`：只处理已知 `action`，未知的按 SILENT + 日志。
- 本契约与 `event.py`、`dispatch.py`、`CLEAN_DECISION` / `READ_OP_RESULT` 的实现必须同步更新。
  这两个代码节点的 Python 由 `tests/test_chatflow_dsl.py` 直接执行，改图时必须同步过测试。
- `MEMO_OP` 与 §3 的写接口属于**新增**，未升级 `version`：不认识 `MEMO_OP` 的旧插件
  只会把它当未知 action 记日志并静默，不会误发——所以只能配合“图与提示词一起切换”，
  不能只换提示词。
