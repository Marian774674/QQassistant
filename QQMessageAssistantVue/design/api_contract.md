# QQ 消息智能助手 · 接口文档（API Contract）

> 本文是 Spring Boot 端 + LangBot 端的**对外接口契约**，供开发时对照实现。
> 组件职责见 [`architecture.md`](../../../../../design/architecture.md)；表结构见 [`database_schema.md`](../../../../../design/database_schema.md)；各接口的设计出处见 [`optimization_schemes.md`](../../../../../design/optimization_schemes.md)。
> 状态：**草案 → 定稿**。实现前如对某个字段有疑问，先改本文档再改代码。

---

## 一、通用约定

| 项 | 约定 |
|---|---|
| Base URL | `http://localhost:8080`（Spring Boot，端口见 `application.yaml`） |
| 统一响应体 | `{ "code": int, "message": string, "data": any }` |
| 成功 | `code = 0`，HTTP 200 |
| 业务/参数错误 | `code = 400`，HTTP 400，`message` 为可读原因 |
| 系统错误 | `code = 500`，HTTP 500 |
| 时间格式 | ISO-8601 本地时间，如 `2026-10-15T14:30:00`（`LocalDateTime` 默认序列化） |
| 枚举 | 字符串字面量，取值见各接口字段说明 |
| 鉴权 | 个人项目，暂不做（内网 localhost） |
| 分页 | 暂不做；列表接口返回全量（必要时用 `limit` 参数） |
| 幂等 | 见各接口说明；`check` 类接口可重复调用 |

**错误响应示例**
```json
{ "code": 400, "message": "草稿当前状态为 CONFIRMED，不可再处理", "data": null }
```

---

## 二、接口总览

### A. Spring Boot 对外 API（本项目 Phase 2 实现）

| 分组 | Method | Path | 调用方 | 幂等 | 用途 |
|---|---|---|---|---|---|
| 草稿 | POST | `/api/drafts` | Dify / LangBot | 否 | 创建草稿 |
| | GET | `/api/drafts` | Vue | 是 | 草稿列表 |
| | POST | `/api/drafts/{id}/confirm` | Vue | 否 | 确认草稿 |
| | POST | `/api/drafts/{id}/reject` | Vue | 否 | 拒绝草稿 |
| 日志 | POST | `/api/message-logs` | LangBot / 发送桥 | 否 | 批量写入消息日志 |
| | GET | `/api/message-logs` | Vue / 总结任务 | 是 | 按会话/方向查询日志 |
| 备忘 | POST | `/api/memos` | Dify | 否 | 创建备忘录 |
| | GET | `/api/memos` | Vue | 是 | 备忘录列表 |
| | GET | `/api/memos/{id}` | Vue | 是 | 备忘详情 |
| | GET | `/api/memos/{id}/reminders` | Vue | 是 | 提醒计划 |
| | PATCH | `/api/memos/{id}` | Vue / LangBot | 否 | 编辑 / 完成 / 取消 |
| 防重 | POST | `/api/collection-tasks/check` | Dify / LangBot | **是** | 查是否已回复 |
| | POST | `/api/collection-tasks/mark` | Dify / LangBot | 否 | 标记已回复 |
| | GET | `/api/collection-tasks/rules` | LangBot | 是 | 拉取前置拦截规则 |
| 拦截规则 | GET | `/api/intercept-rules` | Vue | 是 | 规则列表 |
| | POST | `/api/intercept-rules` | Vue | 否 | 新建规则 |
| | PUT | `/api/intercept-rules/{id}` | Vue | 否 | 修改规则 |
| | DELETE | `/api/intercept-rules/{id}` | Vue | 否 | 删除规则 |
| 画像·事实 | GET | `/api/profile/{senderId}` | Dify | 是 | 会话注入（含事实 + 画像） |
| | PUT | `/api/profile/facts` | Dify | 否 | 高置信事实写入（覆盖） |
| | GET | `/api/profile/facts` | Vue 总控台 | 是 | 事实列表 |
| | POST | `/api/profile/facts` | Vue 总控台 | 否 | 手动新增事实 |
| | PUT | `/api/profile/facts/{id}` | Vue 总控台 | 否 | 手动修改事实 |
| | DELETE | `/api/profile/facts/{id}` | Vue 总控台 | 否 | 手动删除事实 |
| 画像·风格 | GET | `/api/style-profile/{senderId}` | Vue | 是 | 查看当前画像 |
| | POST | `/api/style-profile/update` | Dify 总结工作流 | 否 | 写回画像 |
| 设置 | GET | `/api/settings` | Vue | 是 | 读取全部配置 |
| | PUT | `/api/settings` | Vue | 否 | 更新配置 |
| 文件任务 | GET | `/api/file-tasks` | Vue | 是 | 只读任务列表 |
| | POST | `/api/file-tasks/sync` | LangBot | 否 | 状态镜像同步 |
| 会话映射 | GET | `/api/conversations/resolve` | LangBot | **是** | 取当前 Dify conversation_id |
| | POST | `/api/conversations/bind` | LangBot | 否 | 绑定/覆盖 conversation_id |
| | POST | `/api/conversations/reset` | LangBot | **是** | 标记当前映射失效 |

### B. LangBot 对外 API（Phase 3 实现，供 Spring Boot 调用）

| Method | Path | 调用方 | 用途 |
|---|---|---|---|
| POST | `/api/langbot/send_message` | Spring Boot | 主动发文本 |
| POST | `/api/langbot/send_file` | Spring Boot | 主动发文件 |

---

## 三、草稿中心（`draft`）

### 3.1 `POST /api/drafts` — 创建草稿

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | 是 | `REPLY` \| `FILE` \| `MEMO` \| `FACT` |
| `senderId` | long | 是 | 归属用户 |
| `chatId` | long | 否 | 私聊/群 |
| `title` | string | 否 | 列表展示用标题 |
| `payload` | object | 是 | 结构随 `type` 变化，见下 |

**`payload` 按 type 的结构**

```jsonc
// REPLY —— 拟人回复草稿
// targetType 默认 "private"；targetId 缺省时按 chatId → senderId 回退
{ "content": "好的，我看看", "targetType": "private", "targetId": 2002 }

// FILE —— 文件回传草稿
{ "taskRef": "ft_0001", "fileName": "表单.xlsx", "fileRef": "local://out/ft_0001.xlsx" }

// MEMO —— 备忘草稿（字段同 POST /api/memos）
{ "title": "班会", "content": "周四晚 7 点", "importance": 3, "category": "学业", "dueTime": "2026-10-17T19:00:00" }

// FACT —— 事实草稿
{ "factKey": "专业", "factValue": "计算机科学与技术", "confidence": 0.72 }
```

**响应** `data` = 草稿对象（含 DB 生成的 `id`、`status="PENDING"`）。

**示例**
```jsonc
// 请求
{ "type": "REPLY", "senderId": 1001, "chatId": 2002, "title": "回复草稿",
  "payload": { "content": "好的，我看看" } }

// 响应
{ "code": 0, "message": "ok",
  "data": { "id": 1, "type": "REPLY", "senderId": 1001, "chatId": 2002,
            "title": "回复草稿", "payload": { "content": "好的，我看看" },
            "status": "PENDING", "confirmedAt": null,
            "createdAt": "2026-10-15T14:30:00", "updatedAt": "2026-10-15T14:30:00" } }
```

### 3.2 `GET /api/drafts` — 草稿列表

**Query**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `status` | string | 否 | `PENDING` \| `CONFIRMED` \| `REJECTED`；不传为全部 |
| `type` | string | 否 | `REPLY` \| `FILE` \| `MEMO` \| `FACT`；不传为全部 |

**响应** `data` = 草稿数组，按 `createdAt` 倒序。

### 3.3 `POST /api/drafts/{id}/confirm` — 确认草稿

**行为**：`PENDING → CONFIRMED`，置 `confirmedAt = now`。

**分发（确认后按 `type` 触发，Phase 2 后逐步接入）**

| type | 分发动作 | 接入阶段 |
|---|---|---|
| `REPLY` | 调 LangBot `send_message` 发送 | Plan 2-3（LangBot 接口就绪后） |
| `FILE` | 调 LangBot `send_file` 回传 | Plan 2-3 |
| `MEMO` | 写入 `memo` 表 | Plan 2-2 |
| `FACT` | 写入 `profile_fact` | Plan 2-3 |

**错误**：草稿不存在 → `400`；当前状态非 `PENDING` → `400`。

### 3.4 `POST /api/drafts/{id}/reject` — 拒绝草稿

**行为**：`PENDING → REJECTED`。错误同上。

---

## 四、消息日志（`message_log`）

### 4.1 `POST /api/message-logs` — 批量写入

**请求体**：`MessageLogItem` 数组（LangBot 每条消息进出时调用，异步、可批量）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `senderId` | long | 是 | 用户 |
| `chatId` | long | 否 | 会话 |
| `conversationId` | string | 否 | Dify conversation_id |
| `messageId` | string | 否 | 平台消息 id（桥侧发送成功时回报） |
| `dedupeKey` | string | 否 | 幂等键；相同值重复写入被跳过 |
| `direction` | string | 是 | `IN`（收到）\| `OUT`（发出） |
| `msgType` | string | 是 | `text` \| `image` \| `file` … |
| `content` | string | 否 | 文本内容 |

**响应** `data` = 实际写入条数（int；被 `dedupeKey` 挡掉的条目不计入）。

**示例**
```jsonc
// 请求
[ { "senderId": 1001, "chatId": 2002, "direction": "IN",  "msgType": "text", "content": "在吗" },
  { "senderId": 1001, "chatId": 2002, "direction": "OUT", "msgType": "text", "content": "在" } ]
// 响应
{ "code": 0, "message": "ok", "data": 2 }
```

### 4.2 `GET /api/message-logs` — 查询（Phase 3）

**Query**：`senderId`（可选）、`conversationId`（可选）、`direction`（可选，`IN|OUT`）、`limit`（可选，默认 100，最大 500）。

**响应** `data` = 日志数组，按 `id` 倒序。用于风格总结取原料与全链路排查。

---

## 五、备忘录（`memo`）

### 5.1 `POST /api/memos` — 创建备忘录

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `senderId` | long | 是 | 用户 |
| `chatId` | long | 否 | 会话 |
| `title` | string | 是 | 标题 |
| `content` | string | 否 | 详情 |
| `importance` | int | 是 | 1–5，由 Dify 打分 |
| `category` | string | 否 | `学业`\|`社交`\|`健康`\|`财务`\|`日常`\|`其他` |
| `dueTime` | string | 否 | 明确时间；无则 null |
| `sourceMsgId` | long | 否 | 溯源源消息 |

**行为**：入库 `status=ACTIVE`，并按 `importance` 依**提醒策略表**展开 `memo_reminder` 行：

| 级别 | 提前量 × 次数 |
|---|---|
| 5 | 24h / 2h / 30min（3 次） |
| 4 | 24h / 1h（2 次） |
| 3 | 2h（1 次） |
| 2 | 当天（1 次） |
| 1 | 无 |

**响应** `data` = 备忘对象（含 `id`、`status`）。

> `dueTime` 反序列化是**宽松**的：`2026-10-29T19:00:00`、`2026-10-29 19:00`、
> `2026-10-29T19:00:00+08:00`、`2026-10-29` 都能收。LLM 输出的时间格式不稳定，
> 不应因为一个空格就 500 丢掉整条备忘。

### 5.2 `GET /api/memos` — 备忘录列表

**Query**：`status`（可选，`ACTIVE`\|`REMINDED`\|`AWAITING_REPLY`\|`DONE`\|`CANCELLED`\|`OVERDUE`）、`senderId`（可选）。

### 5.3 `GET /api/memos/{id}` — 备忘详情

**响应** `data` = 备忘对象；不存在时 `code=400`。

### 5.4 `GET /api/memos/{id}/reminders` — 提醒计划

**响应** `data` = `MemoReminder[]`，按 `remindAt` 升序：

| 字段 | 说明 |
|---|---|
| `id` | 提醒 id |
| `offsetLabel` | `24h` \| `2h` \| `1h` \| `30min` \| `当天` |
| `remindAt` | 应该推送的时间 |
| `status` | `PENDING` \| `SENT` \| `CANCELLED` |
| `sentAt` | 实际推送时间 |

**说明**：改过 `importance`/`dueTime` 后旧档位保留为 `CANCELLED`（审计用），前端只展示
`PENDING` + `SENT`，需要时再展开已取消项。

### 5.5 `PATCH /api/memos/{id}` — 编辑 / 完成 / 取消

**请求体**（部分更新，传哪个改哪个）

| 字段 | 类型 | 说明 |
|---|---|---|
| `title` | string | 编辑标题 |
| `content` | string | 编辑详情 |
| `importance` | int | 改重要性 → **重算 `memo_reminder`** |
| `category` | string | 同上枚举，非法值返回 400 |
| `dueTime` | string | 改时间 → **重算 `memo_reminder`** |
| `status` | string | `DONE`（完成）\| `CANCELLED`（取消） |

**QQ 闭环**：用户在 QQ 回「完成/取消」时，LangBot 识别后也调本接口（LangBot 维护「当前激活提醒 `memo_id` → 用户」映射，才能路由到正确 memo）。

**行为**：改 `status` 为 `DONE`/`CANCELLED` 时，同步把该 memo 未发的 `memo_reminder` 置 `CANCELLED`。

---

## 六、统一回复防重（`collection_task`）

### 6.1 `POST /api/collection-tasks/check` — 查是否已回复（幂等）

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `chatId` | long | 是 | 群/会话 |
| `taskType` | string | 是 | 任务类型，如 `学号收集` |
| `taskKey` | string | 是 | `chatId + taskType + 时间窗口` 归一化串 |

**响应**
```json
{ "code": 0, "message": "ok", "data": { "replied": false } }
```

### 6.2 `POST /api/collection-tasks/mark` — 标记已回复

**请求体**：同上三字段。**行为**：upsert 到 `UNIQUE(chat_id, task_key)`，置 `status=REPLIED`、`repliedAt=now`。

**响应** `data` = true。

### 6.3 `GET /api/collection-tasks/rules` — 拉取前置拦截规则

**调用方**：LangBot（启动 + 定时轮询，实现**热更新**）。
**响应** `data` = 启用中的拦截规则数组（按 `priority` 升序）：

```json
{ "code": 0, "message": "ok",
  "data": [ { "id": 1, "name": "学号", "keywords": "学号,姓名,专业",
              "regex": "(学号|姓名|专业)\\s*[:：]?\\s*", "replyTemplate": "学号 2023001，姓名 张三",
              "priority": 10 } ] }
```

> 命中后 LangBot **仍须**调 `check` 防重，再决定是否发送。

---

## 七、拦截规则管理（`intercept_rule`）

### 7.1 `GET /api/intercept-rules` — 列表
**响应** `data` = 全部规则（含 `enabled=false`）。

### 7.2 `POST /api/intercept-rules` — 新建
**请求体**：`{ name, keywords, regex, replyTemplate, priority, enabled }`（`name` 必填，其余可选）。

### 7.3 `PUT /api/intercept-rules/{id}` — 修改
**请求体**：同新建，部分更新。

### 7.4 `DELETE /api/intercept-rules/{id}` — 删除

---

## 八、画像：事实记忆（`profile_fact`）

### 8.1 `GET /api/profile/{senderId}` — 会话注入（Dify 用）

**响应** `data` = `{ facts: {...}, stylePrompt: "..." }`——Dify 在会话开始时注入。
```json
{ "code": 0, "message": "ok",
  "data": { "facts": { "学号": "2023001", "姓名": "张三", "专业": "计算机科学与技术" },
            "stylePrompt": "说话简短、常用‘嗯/好的’、偶尔发表情" } }
```

### 8.2 `PUT /api/profile/facts` — 高置信事实写入（Dify 用）

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `senderId` | long | 是 | 主语归属为「本人」才高置信写 |
| `factKey` | string | 是 | `学号`\|`姓名`\|`专业`\|`目标`… |
| `factValue` | string | 是 | 值 |
| `confidence` | number | 否 | 0–1 |

**行为**：upsert 到 `UNIQUE(sender_id, fact_key)`（**改口直接覆盖**），`source=AUTO`。

### 8.3 事实增删改查（Vue 记忆总控台）

| Method | Path | 请求体 / 参数 | 说明 |
|---|---|---|---|
| GET | `/api/profile/facts` | `?senderId=` | 事实列表 |
| POST | `/api/profile/facts` | `{ senderId, factKey, factValue }` | 新增（`source=MANUAL`） |
| PUT | `/api/profile/facts/{id}` | `{ factKey?, factValue? }` | 修改 |
| DELETE | `/api/profile/facts/{id}` | — | **物理删除**（见 database_schema.md 说明） |

---

## 九、画像：风格（`style_profile`）

### 9.1 `GET /api/style-profile/{senderId}` — 查看当前画像
**响应** `data` = `{ senderId, profilePrompt, lastSummarizedAt }`。

### 9.2 `POST /api/style-profile/update` — 写回画像（Dify 总结工作流用）

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `senderId` | long | 是 | 用户 |
| `profilePrompt` | string | 是 | 新的画像正文 |

**行为**：upsert 到 `UNIQUE(sender_id)`，置 `lastSummarizedAt=now`。
> 更新后由 Spring Boot 调 **Dify 应用配置 API** 把该画像写进用户画像 Prompt（Phase 4 确认调用方式）。

---

## 十、设置（`app_setting`）

### 10.1 `GET /api/settings` — 读取全部配置
**响应** `data` = kv 对象：
```json
{ "code": 0, "message": "ok",
  "data": { "summary_frequency": "DAILY_7D", "timezone": "Asia/Shanghai" } }
```
> `summary_frequency` 取值：`DAILY_7D`（每日 + 近 7 天）\| `DAILY_TODAY`（每日仅当日）\| `WEEKLY`（每周）。

### 10.2 `PUT /api/settings` — 更新配置
**请求体**：kv 对象（部分更新），如 `{ "summary_frequency": "WEEKLY" }`。

---

## 十一、文件任务镜像（`file_task`）

### 11.1 `GET /api/file-tasks` — 只读列表（Vue）
**Query**：`senderId`（可选）、`status`（可选）。
**响应** `data` = 任务数组：`{ id, taskRef, senderId, chatId, fileName, status, detail, createdAt, updatedAt }`。
> `status`：`PENDING`\|`PROCESSING`\|`NEED_INPUT`\|`AWAITING_CONFIRM`\|`SENT`\|`FAILED`\|`CANCELLED`。

### 11.2 `POST /api/file-tasks/sync` — 状态镜像同步（LangBot）
**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `taskRef` | string | 是 | LangBot 侧 taskId（幂等键） |
| `senderId` | long | 是 | 用户 |
| `chatId` | long | 否 | 会话 |
| `conversationId` | string | 否 | 触发任务的 Dify conversation_id |
| `fileName` | string | 否 | 文件名 |
| `request` | string | 否 | 用户原始填写要求 |
| `pendingRequest` | string | 否 | 等待文件时挂起的要求 |
| `status` | string | 是 | 见上枚举 |
| `missing` | string[] | 否 | Dify 判定仍缺的字段 |
| `confidence` | number | 否 | 填写置信度 0-1 |
| `needsConfirm` | boolean | 否 | 是否强制人工确认 |
| `fileRef` | string | 否 | 输入文件引用 `local://...` |
| `outputRef` | string | 否 | 产物文件引用 `local://...` |
| `sourceMessageId` | long | 否 | 源 QQ 消息 id |
| `idempotencyKey` | string | 否 | 重复投递去重 |
| `detail` | object | 否 | 状态详情（JSON 镜像） |

**行为**：按 `taskRef` upsert；缺省字段不覆盖已有值。**只写不回读业务逻辑**——状态机真身在 LangBot 插件内。
FILE 草稿确认发送成功后，Spring 直接把对应 `taskRef` 的 `status` 置为 `SENT`。

---

## 十二、会话映射（`conversation_session`）

> 用于把 QQ 会话与 Dify `conversation_id` 绑定，使 Dify 能跨消息看到上下文。
> 插件侧另有本地 JSON 兜底，Spring 是持久化真相源。契约详见 `agent_contract.md` §1.2 / §4。

### 12.1 `GET /api/conversations/resolve` — 解析当前会话

**Query**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `botUuid` | string | 是 | LangBot bot 标识（插件配置 `bot`） |
| `chatType` | string | 是 | `private` / `group` |
| `chatId` | long | 否 | 群号；私聊可省略（服务端归一为 0） |
| `senderId` | long | 是 | QQ 号 |

**响应** `data` = `{ "conversationId": "..." }`；没有 ACTIVE 映射时为 `null`。

### 12.2 `POST /api/conversations/bind` — 绑定 / 覆盖

**请求体**：`{ botUuid, chatType, chatId, senderId, conversationId }`。
**行为**：按 `(botUuid, chatType, chatId, senderId)` upsert，状态置 `ACTIVE` 并刷新 `lastActiveAt`。
**响应** `data` = 会话映射实体。

### 12.3 `POST /api/conversations/reset` — 标记失效

**请求体**：`{ botUuid, chatType, chatId, senderId }`。
**行为**：状态置 `RESET`；下次 `bind` 会覆盖为新的 `conversation_id`。不存在时幂等返回成功。

---

## 十三、LangBot 对外 API（Phase 3 实现）

> Spring Boot 是「提醒发起者」「草稿确认执行者」，但发 QQ 必须经 LangBot。以下接口由 **LangBot 暴露、Spring Boot 调用**。文本与文件是**两套契约，不混用**。

### 13.1 `POST /api/langbot/send_message` — 发文本

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `target` | object | 是 | `{ "type": "private"\|"group", "id": 2002 }` |
| `content` | string | 是 | 文本 |
| `delayMs` | int | 否 | 拟人延迟（毫秒）；不传由 LangBot 按字数计算 |
| `bizRef` | string | 否 | 业务引用，如 `memo:123` / `draft:45`；LangBot 据此做 QQ 内闭环路由与幂等 |

### 13.2 `POST /api/langbot/send_file` — 发文件

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `target` | object | 是 | 同上 |
| `fileRef` | string | 是 | 文件引用（LangBot 本地路径或任务引用） |
| `fileName` | string | 否 | 展示文件名 |
| `bizRef` | string | 否 | 业务引用，如 `draft:45` |

**响应**（两者一致）：`{ "code": 0, "message": "ok", "data": { "messageId": "..." } }`。

**桥的实现约束**（Phase 3 定稿）：
- 发送**绝不自动重试**：宁可返回失败，也不能重复发消息。
- `bizRef` 相同的请求 24h 内幂等：直接返回上次的 `messageId`，不再发送。
- 同一 `bizRef` **同时在途**时，后到的请求返回 HTTP 409，绝不并发发两条；桥内的占位只在成功后才落盘。
- 幂等判断必须在文件存在性检查之前：产物文件被清理后再重试确认，仍应返回上次的 `messageId`。
- 文件通道回落 NapCat **只对 LangBot 的确定性拒绝（400/404/405）**；超时与 5xx 可能已送达，绝不回落，避免重复发文件。
- 拟人延迟只在 `private` 生效；`delayMs` 显式传入时以请求为准（定时提醒传 `0`，不排队等待）。
- 发送成功后由桥写 `OUT` 日志（带 `dedupeKey=out:<messageId>`）；日志失败不影响已成功的发送。
- 幂等状态文件默认落在 `send_bridge/data/sent.json` 的**绝对路径**，与启动目录无关。
- 插件自行发出的内容（`SEND` 文本、`FILE_FILL` 回传文件、`AWAITING_CONFIRM` 提示）由插件写 `OUT` 日志；插件直发文件不经过桥，**必须**自己补一条 `msgType=file` 的 `OUT` 记录，否则 `message_log` 只能看到文本。
- 业务/参数错误返回 HTTP 400 + `code: 400`；上游失败返回 HTTP 500 + `code: 500`。

> **实现说明（Phase 3 定稿）**：上面是本项目对 Spring Boot 的**稳定契约**，由 Python 侧 `send_bridge`（默认 `http://localhost:5301`）实现，不是 LangBot 原生路径。
> - `send_message` → 转发到 LangBot 官方 API `POST /api/v1/platform/bots/{bot_uuid}/send_message`（`X-API-Key` 鉴权，`target_type` 为 `person|group`，`message_chain=[{type:"Plain",text:...}]`），并在桥内完成拟人延迟与 OUT 日志。
> - `send_file` → 若 LangBot 官方 `send_message` 实测接受 `File` 组件则同样转发；否则由桥调 NapCat OneBot 11 的 `upload_private_file` / `upload_group_file`。
> 详见 `plans/2026-10-18-phase3-3-send-bridge.md`。

---

## 十四、待 Phase 4 确认的集成点

- **Dify 应用配置 API**：`POST /api/style-profile/update` 后更新画像 Prompt 的调用方式、鉴权、字段名。
- **NapCat 文件收发 API**：Phase 3 随 `send_file` 一并确认。
- **Dify 侧 Tool 定义**：Dify 调上述 Spring Boot API 的 Tool 参数 schema，Phase 4 编写。
