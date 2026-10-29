# Dify Agent 契约（事件输入 / 决策输出）

> 本文件是 **Dify Chatflow ↔ LangBot 插件** 的唯一接口准据。
> 插件按本契约发送事件、校验决策；Dify 按本契约输出 JSON。
> 任何一侧改字段，先改本文件，再改代码。

## 0. 职责边界

| 能力 | 归属 | 说明 |
|---|---|---|
| 意图识别、对话状态、是否等待文件、填什么 | **Dify** | 语义与决策 |
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
在 Chatflow 正式启用前，插件只能发送 `facts` / `style_prompt`（当前实现），
启用后一次性补齐。

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

---

## 2. 决策输出（Dify → 插件）

Chatflow 的 **Answer 节点只输出纯 JSON 文本**，不要 Markdown 代码块、不要解释文字。
插件会剥离 ``` 围栏后再解析；解析失败**不会**把原文发给用户。

### 2.1 通用信封

```json
{
  "version": 1,
  "action": "SEND | DRAFT | SILENT | MEMO | FACT | MEMO_CLOSE | WAIT_FILE | ASK | FILE_FILL",
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

#### MEMO

```json
{"version":1,"action":"MEMO","content":"记下了。",
 "memo":{"title":"交实验报告","content":"周五前交","importance":3,
         "category":"学业","dueTime":"2026-10-30T18:00:00"}}
```

插件：建备忘；`content` 可选，作为确认语。

#### FACT

```json
{"version":1,"action":"FACT",
 "fact":{"factKey":"学号","factValue":"2023001","confidence":0.95}}
```

插件：`confidence >= fact_auto_threshold` 自动写事实；否则建 FACT 草稿。

#### MEMO_CLOSE

```json
{"version":1,"action":"MEMO_CLOSE","content":"这件事已完成。",
 "close":{"memo_id":123,"status":"DONE"}}
```

插件：按 `memo_id` 更新；为空时查该用户最新 `AWAITING_REPLY` 备忘。
`status ∈ {DONE, CANCELLED}`。

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

## 3. 校验与降级（插件侧）

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

## 4. 状态归属

| 状态 | 主存储 | 备份 | 生命周期 |
|---|---|---|---|
| `conversation_id` | Spring `conversation_session` | 插件本地 `_conversations.json` | 7 天 TTL / 用户“重新开始” |
| `pending_file_request` | Dify conversation variable | Spring `file_task.pending_request` | `ttl_seconds`（默认 900s） |
| `pending_file_task_ref` / `awaiting` | Dify conversation variable | 插件 `_active/<sender>.txt` + Spring `file_task` | 任务结束或过期 |
| 文件任务全文 | 插件 `task.json` | Spring `file_task.detail` | 任务保留至手动清理 |
| 草稿/备忘/事实/消息日志 | Spring | — | 业务生命周期 |

---

## 5. 错误码与日志字段

插件日志（`file_store.debug`）至少包含：

```
event=<text|file> sender=<id> chat=<id|-> conv=<id|-> task=<ft_xxx|-> action=<ACTION> result=<ok|fallback|error>
```

Dify 返回的错误体保留前 500 字符；QQ 签名 URL 必须脱敏（只留 host + 状态码）。

---

## 6. 版本策略

- `version` 当前为 `1`。新增字段向后兼容；删除/改义字段必须升 `version`。
- 插件遇到更高 `version`：只处理已知 `action`，未知的按 SILENT + 日志。
- 本契约与 `decision.py`、`dispatch.py` 的实现必须同步更新。
