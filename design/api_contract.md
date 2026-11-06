# QQ 消息智能助手 · 接口文档（API Contract）

> 本文是 Spring Boot 端 + LangBot 端的**对外接口契约**，供开发时对照实现。
> 组件职责见 [`architecture.md`](./architecture.md)；表结构见 [`database_schema.md`](./database_schema.md)；各接口的设计出处见 [`optimization_schemes.md`](./optimization_schemes.md)。
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
| 鉴权 | 网页接口走登录态；`/api/agent/**` 走 `X-Agent-Token`；`/api/auth/captcha`、`/api/auth/login`、`/api/auth/qq/**`、`/api/health` 免登录（见 §15 / §16） |
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
| 登录 | GET | `/api/auth/captcha` | 登录页 | 否 | 图形验证码（密码登录用） |
| | POST | `/api/auth/login` | 登录页 | 否 | 账号 + 密码 + 图形码换会话 |
| | GET | `/api/auth/qq/status` | 登录页 | 是 | QQ 登录是否可用 + 脱敏主人号 |
| | POST | `/api/auth/qq/code` | 登录页 | 否 | 机器人私聊推送 6 位一次性码 |
| | POST | `/api/auth/qq/verify` | 登录页 | 否 | 验证码换会话（与密码登录同源） |
| | POST | `/api/auth/logout` | Vue | 是 | 注销当前会话 |
| | GET | `/api/auth/me` | Vue | 是 | 当前登录账号 |
| | POST | `/api/auth/password` | Vue | 否 | 修改密码 |
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
| | GET | `/api/memos/open` | LangBot（→Dify） | 是 | 未终结备忘摘要，供模型指认「改哪条」 |
| | GET | `/api/memos/{id}/history` | Vue | 是 | 变更记录（AI / 手动） |
| Agent 写入口 | POST | `/api/agent/ops` | Dify HTTP 节点 | **是（`eventId`）** | 备忘新建/修改/完成/取消 |
| 防重（兼容保留） | POST | `/api/collection-tasks/check` | 暂无调用方 | **是** | 查是否已回复 |
| | POST | `/api/collection-tasks/mark` | 暂无调用方 | 否 | 标记已回复 |
| 前置噪音判定 | POST | `/api/agent/noise-filter/check` | LangBot 插件 | 是 | 单条消息是否噪音（取代原拦截规则，§17.7） |
| 画像·事实 | GET | `/api/profile/{senderId}` | Dify | 是 | 会话注入（含事实 + 画像） |
| | PUT | `/api/profile/facts` | Dify | 否 | 高置信事实写入（覆盖） |
| | GET | `/api/profile/facts` | Vue 总控台 | 是 | 事实列表 |
| | POST | `/api/profile/facts` | Vue 总控台 | 否 | 手动新增事实 |
| | PUT | `/api/profile/facts/{id}` | Vue 总控台 | 否 | 手动修改事实 |
| | DELETE | `/api/profile/facts/{id}` | Vue 总控台 | 否 | 手动删除事实 |
| 画像·风格 | GET | `/api/style-profile/{senderId}` | Vue | 是 | 查看当前画像 |
| | POST | `/api/style-profile/update` | Vue 手动编辑 | 否 | 写回画像 |
| | POST | `/api/style-profile/summarize` | Vue / 兜底 | 否 | 立即按消息日志总结一次 |
| 设置 | GET | `/api/settings` | Vue | 是 | 读取全部配置 |
| | PUT | `/api/settings` | Vue | 否 | 更新配置（5.5 起只接受白名单键，见 §10.2） |
| 噪音过滤 | GET | `/api/settings/noise-filter` | Vue | 是 | 当前规则 + 出厂默认值（§17.1） |
| | PUT | `/api/settings/noise-filter` | Vue | 是 | 保存四个 `noise_filter_*` 键（§17.2） |
| | POST | `/api/settings/noise-filter/preview` | Vue | 是 | 按**草稿规则**试跑，不写库（§17.3） |
| 文件任务 | GET | `/api/file-tasks` | Vue | 是 | 只读任务列表 |
| | POST | `/api/file-tasks/sync` | LangBot | 否 | 状态镜像同步 |
| 会话映射 | GET | `/api/conversations/resolve` | LangBot | **是** | 取当前 Dify conversation_id |
| | POST | `/api/conversations/bind` | LangBot | 否 | 绑定/覆盖 conversation_id |
| | POST | `/api/conversations/reset` | LangBot | **是** | 标记当前映射失效 |
| 认识的人 | GET | `/api/contacts` | Vue | 是 | 人物列表 |
| | POST | `/api/contacts` | Vue / `/api/ask` | 否 | 手工认人（`source=USER`） |
| | DELETE | `/api/contacts/{id}` | Vue | 否 | 删除一个称呼 |
| 历史查询 | POST | `/api/ask` | Vue | 否 | 一句话回查聊天记录、事实、备忘、文件 |
| 记忆归纳 | POST | `/api/memory/rebuild` | Vue / 运维 | 否 | 游标清零，全量补算事实 |
| 自动回复 | GET | `/api/chat-settings/reply-context` | LangBot | 是 | 开关 + 限流快照 + 子提示词 + 最近消息 |
| 掉线补拉 | GET | `/api/agent/napcat/targets` | LangBot 插件 | 是 | 待补拉会话 + 各自游标（上限 50） |
| | POST | `/api/agent/napcat/cursor` | LangBot 插件 | **是（只前进）** | 推进某会话已补到的 `message_seq` |
| Token 账本 | POST | `/api/agent/token-usage` | LangBot 插件 | 否 | 上报一笔模型用量（§17.4） |
| | GET | `/api/token-usage/summary` | Vue | 是 | 7/30/90 天汇总 + 按任务/来源/日/会话分账（§17.5） |
| | GET | `/api/token-usage/recent` | Vue | 是 | 最近明细，默认 50 条（§17.6） |

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
// content = 要发出去的话（草稿卡主句），**必填**：空正文的草稿点「发送」只会把空串
//   发给发送桥，QQ 上什么都没有、草稿却被标成 CONFIRMED。
// sourceContent = 对方说的那句（卡片顶部的引文），免得确认时看不出在回谁；
// sourceSenderName / sourceChatName = 谁在哪个群说的，私聊时 chatName 为空串。
// title 对 REPLY 没有意义，留空即可——前端渲染的是 sourceContent + content，
// 把 content 再塞进 title 会让同一句话在卡片上出现两次。
{ "content": "好的，我看看", "sourceContent": "明天几点呀？",
  "sourceSenderName": "小张", "sourceChatName": "三班群",
  "targetType": "private", "targetId": 2002 }

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

**请求体**（可省略；两个字段各自只对一种类型有效）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `aliases` | string | 否 | 仅 `NAMING`：会话名字，逗号分隔，第一个用于展示 |
| `content` | string | 否 | 仅 `REPLY`：**改写后要发出去的正文**。不传 = 按模型原本写的发；传空串/纯空白 = `400` |

> `content` 是「改发什么」，不是「改发不发」。落库时写回 `payload.content`（**实际发出的那句话**），
> 原正文另存 `payload.originalContent` 并置 `payload.edited = true`，方便回头比对「模型写的」和「人改的」。
> 给非 `REPLY` 类型传 `content` 会直接 `400`，不静默丢弃——否则前端传错字段时用户会以为「改了却没生效」。

**行为**：`PENDING → CONFIRMED`，置 `confirmedAt = now`。

**分发（确认后按 `type` 触发，Phase 2 后逐步接入）**

| type | 分发动作 | 接入阶段 |
|---|---|---|
| `REPLY` | 调 LangBot `send_message` 发送（有 `content` 则发改写后的） | Plan 2-3（LangBot 接口就绪后） |
| `FILE` | 调 LangBot `send_file` 回传 | Plan 2-3 |
| `MEMO` | 写入 `memo` 表 | Plan 2-2 |
| `FACT` | 写入 `profile_fact` | Plan 2-3 |

**错误**：草稿不存在 → `400`；当前状态非 `PENDING` → `400`；
`REPLY` 的 `payload.content` 为空 → `400`（在状态改成 `CONFIRMED` **之前**拦，
不靠事务回滚兜底——回滚会把「发没发出去」和「状态对不对」绑在一起）；
`content` 超过 2000 字 → `400`；非 `REPLY` 类型传了 `content` → `400`。

### 3.4 `POST /api/drafts/{id}/reject` — 拒绝草稿

**行为**：`PENDING → REJECTED`。错误同上。

---

## 四、消息日志（`message_log`）

### 4.1 `POST /api/message-logs` — 批量写入

**请求体**：`MessageLogItem` 数组（LangBot 每条消息进出时调用，异步、可批量）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `senderId` | long | 是 | 用户 |
| `senderName` | string | 否 | 发送者昵称；拿不到可省 |
| `chatId` | long | 否 | 会话。**私聊必须省略**——填了就会被当成群消息，私聊会话在网页上消失 |
| `chatName` | string | 否 | 群名；私聊不填 |
| `conversationId` | string | 否 | Dify conversation_id |
| `messageId` | string | 否 | 平台消息 id（桥侧发送成功时回报） |
| `dedupeKey` | string | 否 | 幂等键；相同值重复写入被跳过 |
| `direction` | string | 是 | `IN`（收到）\| `OUT`（发出） |
| `msgType` | string | 是 | `text` \| `image` \| `file` … |
| `content` | string | 否 | 文本内容 |
| `replyKind` | string | 否 | 仅 `OUT`：`TASK` \| `CHAT` |
| `createdAtEpochSeconds` | long | 否 | 消息发生时刻（Unix 秒）。**只有补拉会传**；不传按写入时刻 |

**去重键约定（2026-11 起）**：实时路径与掉线补拉统一用 `dedupeKey = "msg:" + message_id`。

补拉会把同一批消息从 NapCat 历史里再取一遍，两边唯一对得上的就是 `message_id`；
实时路径以前用随机 uuid，同一条消息会在 `message_log` 里出现两次。
拿不到 `message_id` 时退化为唯一键——此时只保「同一批重试」不重复。

**`createdAtEpochSeconds` 为什么是 epoch 而不是 ISO 串**：插件跑在容器里，
容器时区未必是 `Asia/Shanghai`，传墙钟时间会整体偏 8 小时；epoch 由后端按 JVM 时区换算，
和实时日志（`LocalDateTime.now()`）同一个轴。整理的时间窗、夜间归纳的批次都依赖这个轴。

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

**行为**：入库 `status=ACTIVE`，并按 `category`（**不是 importance**）依**提醒策略表**展开
`memo_reminder` 行。缴费和买牛奶的紧急程度不是一回事，一张按重要度的表必然对其中一个是错的。

| 类型 | 默认计划（末尾都带「到期时」） |
|---|---|
| 学业 | 前一天 20:00、提前 1 小时、到期时 |
| 财务 | 前一天 10:00、当天 08:00、到期时 |
| 社交 | 提前 2 小时、提前 30 分钟、到期时 |
| 健康 / 日常 | 提前 30 分钟、到期时 |
| 其他（含 `category` 为空） | 提前 1 小时、到期时 |

**到期时提醒是强制的**：只要 `dueTime` 还在未来、且 `importance > 1`，每条备忘都会排一条
标签 `截止` 的提醒（中文 `到期时`）。类型表只管「提前多久」，管不了「到点必须响一次」；
它固定占一个 `max-per-memo` 名额，名额不够时先砍离到期最远的提前档，绝不砍它。

- 计划**可配置**：`app.reminder.policy`（`application.yaml`），改完重启生效。
  写成**列表**、分类名放在 `category` 字段里，不要写成 `学业: [...]` 这种中文 key 的映射——
  Spring 属性名只认 ASCII，中文 key 会在绑定阶段被丢掉，YAML 改了会静默不生效：

  ```yaml
  app:
    reminder:
      max-per-memo: 3        # 一条备忘最多推几次（含到期那一条）
      dedupe-minutes: 20     # 两个点相差小于它就合并
      policy:
        - category: 学业
          points: ['-1d@20:00', '-1h']
        - category: 其他
          points: ['-1h']
  ```

  列表是整份覆盖：YAML 里写了 `policy` 就不再使用 Java 出厂表，没写的分类落到「其他」。
- 标签三种写法：`@HH:mm`（锚点当天此刻）、`±Nd@HH:mm`（锚点日期 ±N 天再取此刻）、
  `±N[dhm]`（相对锚点提前的天/小时/分钟）。这些标签原样存进 `memo_reminder.offset_label`。
- **锚点归一化**：`dueTime` 的时刻部分是 `00:00`（LLM 只给了日期）时，锚点取当天 **09:00**，
  否则纯日期备忘会算出凌晨 00:00 / 22:00 / 23:30 的提醒。
- 生成时依次：丢掉已过去的点 → 合并相差 `< dedupe-minutes`（默认 20）的点 →
  截断到 `max-per-memo - 1`（给到期那条留位，默认总共 3 条）→ 补「截止」。
  与「截止」相差 `< dedupe-minutes` 的提前档让位给「截止」，而不是反过来。
- 临到点才记的事不需要额外兜底：提前档全过去时，「截止」就是那条提醒
  （旧的 `立即` 标签不再生成，只保留中文翻译给历史数据用）。
- `importance = 1` 表示「纯信息、只存档」，不排任何提醒；其余级别不影响时间点。

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
| `offsetLabel` | 档位标签，如 `-1d@20:00`、`-1h`、`@08:00`、`截止`（到期时）；旧数据里可能还有 `24h`、`立即` 之类 |
| `offsetText` | 后端翻译好的中文，如「前一天 20:00」。前端直接显示，**不要**在前端维护映射表 |
| `remindAt` | 应该推送的时间 |
| `status` | `PENDING` \| `SENT` \| `CANCELLED` |
| `sentAt` | 实际推送时间 |

**说明**：改过 `category`/`importance`/`dueTime` 后旧档位保留为 `CANCELLED`（审计用）。
前端把 `CANCELLED` 项画成删除线展示。

### 5.4.1 `GET /api/memos/reminder-preview` — 提醒预览

**Query**：`category`（可选）、`dueTime`（可选，宽松格式）、`importance`（可选）。

编辑一条还没保存的备忘时，用它显示「将在这几个时刻提醒你」。响应 `data` =
`[{label, text, remindAt}]`，与真实排程走**同一份策略**，不会出现预览和实际不一致。
`dueTime` 为空返回 `[]`；解析不了返回 `code=400`。

### 5.4.2 `POST /api/memos/{id}/replan` — 重新排提醒

把该备忘未发的提醒置为 `CANCELLED`（不删除），再按当前策略重排。响应 `data` = 新的提醒计划。
用于把部署前按旧策略排好的提醒换成新策略；`DONE`/`CANCELLED` 的备忘返回 `code=400`。

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

### 5.6 `GET /api/memos/open` — 未终结备忘摘要

**Query**：`senderId`（必填）、`limit`（默认 20，clamp 1–50）

**响应** `data` = `MemoOpenItem[]`：

| 字段 | 说明 |
|---|---|
| `id` | 备忘 id —— **模型唯一可以引用的 id** |
| `title` | 标题 |
| `content` | 正文，截 60 字 |
| `dueTime` | 可空 |
| `status` | `ACTIVE` / `REMINDED` / `AWAITING_REPLY` / `OVERDUE` |
| `importance` | 1–5 |
| `category` | 分类 |

只含未终结状态（`DONE` / `CANCELLED` 不出现）；有 `dueTime` 的按时间升序在前，
没有的按 `updated_at` 倒序在后。插件把它 `json.dumps` 后作为 `open_memos` 喂给 Dify（见 `agent_contract.md` §1.4）。

---

### 5.7 `GET /api/memos/{id}/history` — 变更记录

**响应** `data` = `MemoChangeLog[]`，新的在前：

| 字段 | 说明 |
|---|---|
| `id` | 记录 id |
| `memoId` / `senderId` | 归属 |
| `source` | `AGENT`（Dify 调用）/ `VUE`（人工） |
| `op` | `CREATE` / `UPDATE` / `CLOSE` |
| `eventId` | 幂等键（人工改动为 null） |
| `beforeJson` / `afterJson` | 变更前后快照（整条 memo） |
| `resultJson` | 当时返回给 Dify 的确认信封（重放时直接返回它） |
| `createdAt` | 时间 |

前端只用它展示「时间 / 来源 / 改前改后摘要」；**它是备忘正文之外唯一的历史留存**，
所以提示词要求模型把 `content` 重写成「改动后的完整状态」，而不是在正文里堆历史。

---

### 5.8 `POST /api/agent/ops` — Agent 窄写入口（备忘）

Dify 的 `OP_HTTP` 节点直接调用本接口。**鉴权**：请求头 `X-Agent-Token` 必须等于
`app.agent.token`（环境变量 `AGENT_OPS_TOKEN`）；未配置时整组接口 503（fail closed），
配置了但头不对/缺失返回 401。

**请求体**：全部字段为字符串，空串 = 未提供（见 `agent_contract.md` §3.1）。

| 字段 | 说明 |
|---|---|
| `eventId` | 必填，幂等键 |
| `op` | `memo.create` / `memo.update` / `memo.close` |
| `senderId` | 必填；归属与幂等只认它 |
| `chatId` / `conversationId` / `query` | 审计用，可空 |
| `memoId` | `update`/`close` 用；`close` 为空时取该用户最新未终结备忘 |
| `title` / `content` / `importance` / `category` / `dueTime` / `clearDueTime` / `status` | 同 `MemoPatchRequest` 语义 |

**响应**：`Result<AgentOpsResult>`，`data` = `{op, memoId, status, message, replayed, reminders[]}`。

**错误语义（对 Dify 而言很关键）**：

| 情况 | HTTP | `code` |
|---|---|---|
| 成功 / 重放 | 200 | 0 |
| 业务拒绝（校验、归属、找不到、参数冲突） | **200** | **400** |
| token 缺失/错误 | 401 | 401 |
| 未配置 token | 503 | 503 |
| 未捕获异常 | 500 | 500 |

业务拒绝走 200 是为了让 Dify 的成功分支把 `message`（中文原因）转达给用户；
只有基础设施问题才走非 2xx，由 `OP_FAILED` 给通用道歉。

**幂等**：同一 `eventId` 只执行一次，第二次直接返回上次的 `result_json`（`replayed=true`）。

**归属**：`memoId` 不属于 `senderId` 时一律返回「备忘录不存在」，不区分“不存在”和“不是你的”。

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

### 6.3 已下线：`GET /api/collection-tasks/rules`

原端点供插件启动 / 定时拉取前置拦截规则，已随「拦截规则」整体下线删除。
插件的前置过滤改走 `POST /api/agent/noise-filter/check`（§17.7），
**不再从 Spring 拉规则、也不再本地缓存**。
`check` / `mark` 两个防重端点保留兼容，但当前没有调用方。

---

## 七、拦截规则管理（已下线）

> **2026-11 变更**：用户要求「噪音过滤直接替换原来的拦截规则功能，原来的拦截规则直接废弃」。
> 运行时链路**彻底下线**：`/api/intercept-rules` 四个 CRUD、`GET /api/collection-tasks/rules`、
> 插件侧 `core/rules.py` 规则缓存 / 60s 热更新 / 模板回复 / `check`+`mark` 防重调用全部删除；
> Java 侧实体、Mapper、Service、Controller 也已删除。
> `intercept_rule` / `collection_task` 两张表**保留不 DROP**（存量数据不动，避免不可逆破坏），
> 但不再被任何运行时代码读写；新库仍会按 `db/schema.sql` 建出空表以兼容历史迁移脚本。
>
> 前端 `/rules` 页改为「噪音过滤」配置页（规则读写见 §17.1–17.3），
> 消息入口的单条判定见 §17.7。原「命中规则 → 查防重 → 发模板回复」的流程**不再存在**。

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
> 画像的注入不走 Dify 应用配置 API，而是由插件在每轮 `/chat-messages` 里作为 `style_prompt` 输入带过去
> （见 `agent_contract.md` §1.1）。**风格总结只写库，不实时改 Prompt**（FR C-3）。

### 9.3 `POST /api/style-profile/summarize` — 立即总结一次（手动 / 兜底）

无请求体。**响应** `data` = `true`（写了新画像）/ `false`（没有主人消息、Dify 未配置或模型没产出，旧画像不变）。

**取数**：`message_log` 中 `sender_id = owner_sender_id AND direction = 'IN'` 的消息（主人亲手发的），
窗口按 `summary_frequency`：`DAILY_TODAY` 取当日、`DAILY_7D` / `WEEKLY` 取近 7 天。

> 定时总结走同一套逻辑（`StyleSummaryService`）：每日 00:20 + 每 3 小时兜底 + **应用启动时**各检查一次；
> 到期判定看 `summary_frequency` 与 `style_profile.last_summarized_at`，**宕机错过定时点会在重启后补做**。
> 失败 / 无消息 / 空产出都不推进 `lastSummarizedAt`，下一轮重试。

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

**白名单（5.5）**：只接受 `AppSettingKeys.USER_EDITABLE` 里的键；越权键（`auth_password_hash`、
`memory_cursor`、`last_digest_run` 等系统自己维护的键）返回 `code=400`「配置键不允许从网页修改」，
请求整体不落库。四个 `noise_filter_*` 键也在白名单里，但建议走 §17.1 / §17.2 的专用接口
（保存前会编译正则、校验长度、拒绝嵌套量词，通用接口不做这些检查）。

> **「请求整体不落库」是个连坐条款**：`/chat-settings` 的「保存偏好」一次提交五个键
> （`digest_enabled` + `owner_aliases` + 三个 `digest_*`），只要其中一个不在白名单里，
> 整张表都会被拒——界面上的表现是「弹一句配置键不允许修改，别名和关注点也一起没存上」。
> 5.5 收口时漏了 `digest_enabled`，直到 2026-11 才补上。**加键时对着前端 `putSettings` 的调用点核一遍**，
> `SettingsControllerTest.chatPreferencesAreEditable` 用页面真实发的那段 body 钉住了这件事。

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

## 十四、记忆归纳与历史查询（Phase B）

> 三张新表见 `database_schema.md` §12/§13；Dify 那一侧的分支见 `memory_workflow.md`。
> 共同原则：**模型只做语义判断，认人、校验、落库全在 Spring**。

### 14.1 `GET /api/contacts` — 人物列表

**Query**：`senderId`（可选，按 QQ 号过滤；不传返回全部称呼）。

**响应** `data` = 数组：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | long | 主键 |
| `senderId` | long | QQ 号 |
| `alias` | string | 称呼（`(senderId, alias)` 唯一） |
| `relation` | string \| null | 同学 / 老师 / 室友…… |
| `importance` | int | 0 普通 / 1 一般 / 2 重要 |
| `note` | string \| null | 备注 |
| `source` | string | `AUTO`（夜间归纳写的）/ `USER`（用户认过的） |
| `updatedAt` | string | ISO 本地时间 |

### 14.2 `POST /api/contacts` — 手工认人

**请求体**：`{ senderId, alias, relation? }`。

**行为**：按 `(senderId, alias)` upsert，并把 `source` 强制写成 `USER`——**USER 行夜间任务永不覆盖**。
校验：`alias` 非空且 ≤64 字，`senderId` 必填；失败是 `code!=0` 的中文 message。

**响应** `data` = `contact` 实体。

### 14.3 `DELETE /api/contacts/{id}` — 删除一个称呼

**响应** `data` = `1`。删掉的只是「这个名字对应这个人」这一条，不影响消息与事实。

### 14.4 `POST /api/ask` — 一句话回查

**请求体**

```json
{
  "question": "阿黄什么时候考试",
  "clarify": { "alias": "阿黄", "senderId": 1753800374, "from": "室友" }
}
```

- `clarify` 可选：用户刚回答了「你说的阿黄是谁」时带上。Spring 先按 `USER` 写一条 contact，再拿这个映射直接作答。
- 空问题 → `code!=0`「问题不能为空」。

**响应** `data`

| 字段 | 类型 | 说明 |
|---|---|---|
| `answer` | string | 结论；降级、认不出人或找不到时为空串 |
| `degraded` | boolean | `true` = 作答失败，或模型给了结论但 `used_ids` 越界/为空；结论作废，只给证据 |
| `evidence` | array | `{ type, id, label, content, source?, quote? }`；`type ∈ msg/note/memo/file`，`id` 是纯数字，前端用 `type:id` 做 key |
| `needClarify` | object \| null | `{ alias, candidates: [{ senderId, alias, relation, hint }], occurrences }`；`candidates` 为空表示库里没这个称呼，需要用户补 QQ 号 |
| `scope` | object | 本次检索范围，见下；老前端可忽略 |

`evidence[]` 的 `source` / `quote`：

- `source`：出处，格式化字符串。群聊 `群名 · 人名 · MM-dd HH:mm`；私聊 `QQ 对方QQ · 人名 · MM-dd HH:mm`；
  无发送人名时退回 QQ 号。多条来源用 `；` 连接，最多 3 条，其余以「等 N 条」收尾（N 是**剩余**条数）。
- `quote`：第一条来源消息的原文，截 200 字。

`needClarify.occurrences`：该 alias 在 `message_log`（`direction='IN'`）正文里作为子串出现的行数，
跨全部会话，显示上限 999。未知 alias 才有值；同名多人的歧义分支为 `null`。
`occurrences=0` 时不会触发反问，直接按「没找到」返回。

`scope`：

```json
{
  "semantic": true,
  "lag": 0,
  "rounds": 2,
  "candidateCnt": 47,
  "channels": {
    "note": 3, "msg-person": 5, "msg-mention": 8, "msg-lexical": 12,
    "msg-semantic": 20, "memo": 0, "file": 0
  },
  "message": ""
}
```

| 字段 | 说明 |
|---|---|
| `semantic` | 语义通道是否可用 |
| `lag` | 尚未建立向量的消息条数 |
| `rounds` | 实际跑了几轮检索（1–3；认人反问的终态路径为 0） |
| `candidateCnt` | 去重、预算截断前的候选数（注意是 camelCase；DB 的 `ask_trace.candidate_cnt` 仍是下划线列名） |
| `channels` | **键固定为 7 个，永远出现**：`note / msg-person / msg-mention / msg-lexical / msg-semantic / memo / file`。值为 `null` = 该路没跑、不可用或报错；`0` = 跑了但没有命中。两者不能混 |
| `message` | 给人看的一句话，前端原样显示；空串表示无需解释。**即使 `answer` 非空也可能非空**（如「未登记的联系人，按名字检索」） |

计数口径：每路原始命中、按查询串累加、去重前，跨轮累加；所以各通道之和可以大于 `candidateCnt`。

**流程**（完整链路与终态表见 `intelligent_retrieval.md` §7）：

1. Dify `stage=resolve` 抽 `entities` / `keywords` / `queries` / `time_hint` / `time_from` / `time_to`；
   Spring 额外传 `current_time`（ISO 本地时间，秒精度），供 Dify 换算相对时间；
2. Spring 用 `contact.alias` 精确认人：≥2 个不同 QQ → 立即 `needClarify`（带候选，**不检索**）；
   0 条 → 记为未知 alias、统计 `occurrences` 后照常检索；1 条 → 认下；
3. 检索前同步补跑向量（最多 `app.retrieval.pre-run-index-limit`，默认 500 条），剩余写入 `scope.lag`；
4. 七路召回（全部不带会话过滤）→ RRF 融合去重 → 时间软分段 → 按 30000 字符 / 150 条预算截断；
5. Dify `stage=answer` 在候选集内作答并给出 `used_ids`；Spring 校验 `used_ids ⊆ 候选集`；
6. 答不出（空答案 / 作答失败 / 证据越界）且未到第 3 轮 → Dify `stage=expand` 要新检索串/实体后重查，
   候选集**跨轮累计**；最多 3 轮（含第 1 轮）；
7. 循环结束后按终态表返回结论、证据、`needClarify` 或 `scope.message`。

**不做什么**：不做 QQ 端提问（检索只服务前端 `/api/ask`）；不做会话范围筛选、结果分页；
不做 ANN 索引（全表暴力余弦，50 万条以上再说）；不检索文件正文；不用 Dify Dataset。
多轮追问最多 3 轮（含第 1 轮），不是无限追问。

### 14.5 `POST /api/memory/rebuild` — 全量补算

**行为**：把 `app_setting.memory_job_cursor` 置 `0`，然后循环跑夜间归纳，直到游标不再前进为止（最多 50 批，每批 200 条 `IN` 消息）。

**响应** `data` = 本次累计写入的事实条数。

**注意**：幂等靠 `(subject, predicate, value)` 去重，重复补算不会产生重复行；但**不会删除**已经写进去的旧事实。
这是运维/首次导入用的口子，不是日常开关——日常靠 `app_setting.memory_job_enabled=1` + 每天 00:10 的定时任务。

### 14.5.1 `POST /api/memory/embeddings/rebuild` — 重建语义向量索引

**请求体**：无（当前模型由 `app.embedding.model-id` 决定）。

**行为**：删除该模型的全部 `message_embedding` 行，按批重算整张 `message_log`，
最后重新加载进程内索引。**仅手动触发**，不在夜间自动跑。

**响应** `data` = 本次重新写入的向量行数。向量服务不可用时返回 `0`，且**不删除**已有数据。

### 14.5.2 `GET /api/memory/embeddings/status` — 语义索引状态

**响应** `data`

```json
{ "semantic": true, "indexed": 12345, "lag": 0 }
```

| 字段 | 说明 |
|---|---|
| `semantic` | 语义通道是否可用（向量服务就绪且索引加载成功） |
| `indexed` | 当前进程内索引的向量条数 |
| `lag` | 尚缺向量的 `IN` 消息条数 |

### 14.6 `GET /api/chat-settings/reply-context` — 自动回复的决策快照（LangBot）

**用途**：插件在调 Dify **之前**取一次，一次拿齐「这个会话能不能自动回复、还能说几条、
主人写的人设、群里刚才在聊什么」。分三次请求会让快照之间互相不一致。

**请求参数**

| 参数 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `chatId` | 是 | — | 群号；私聊填对方 QQ 号 |
| `kind` | 否 | `GROUP` | `GROUP` / `PRIVATE` |
| `limit` | 否 | `15` | 最近消息条数，clamp 1–50 |

**响应** `data`

```json
{
  "globalEnabled": true,
  "enabled": true,
  "ownerSenderId": "123456789",
  "replyPrompt": "我是张三，说话随意一点，别提我工作",
  "rateLimit": 20,
  "outLastHour": 3,
  "recentMessages": [
    {"senderName": "阿黄", "direction": "IN", "content": "明天几点集合", "createdAt": "2026-10-30T21:14:00"},
    {"senderName": "", "direction": "OUT", "content": "八点半东门", "createdAt": "2026-10-30T21:15:00"}
  ]
}
```

| 字段 | 语义 |
|---|---|
| `globalEnabled` | `app_setting.reply_enabled` 本身，**不含**会话开关与令牌桶。`false` = 彻底闭嘴，连 `TASK` 一起停 |
| `enabled` | `globalEnabled` **且** `chat_setting.auto_reply_enabled` **且** 令牌桶有余额；没建开关行 = `false`（不报错） |
| `ownerSenderId` | `app_setting.owner_sender_id`；空串 = 还没绑定。插件拿它区分「别人发来的东西」和「主人自己在用工具」 |
| `replyPrompt` | `app_setting.reply_prompt`；空串 = 主人没写 |
| `rateLimit` | `app_setting.reply_rate_limit`，默认 **20**；非法值也回落默认 |
| `outLastHour` | 该会话最近一小时 `direction='OUT' AND reply_kind='CHAT'` 的行数 |
| `recentMessages` | 该会话最近 `limit` 条（两个方向都要），**按时间正序**；`content` 压掉换行、截 200 字 |

**总开关（`app_setting.reply_enabled`）**：一键**彻底闭嘴**。只有明确的
`false` / `0` / `no` / `off` 才算关，**没配过一律当开**——它是紧急闸，
不能因为升级或新装把用户逐个打开的会话顺手关掉。

关掉后**不再产生任何自动发言**：`CHAT` 闲聊、`TASK` 的备忘确认语、文件任务应答全部停。
动作照跑——备忘照建、状态照改、文件照写，只是不往外吐一个字。
（原「拦截规则回复」已随该功能下线删除，见 §七。）
落地方式是在插件最底层压 `reply_text`（`core.reply.silence()`），而不是逐个分支判断：
直接回话的点散在 `file_task` / `dispatch` 等分支里共七八处，漏一个就等于没关。

**两层开关别混成一个字段**：`globalEnabled` 是总开关本身；`enabled` 是
「总开关 AND 会话开关 AND 令牌桶」。会话开关关掉只是「这个群不闲聊」，
`TASK` 仍要发；把两者合并就再也表达不了「彻底闭嘴」。
旧版 Spring 不返回 `globalEnabled` 时，插件按 `true` 处理（否则升级插件会让人全哑）。

**文件任务的例外（2026-11 修正）**：文件消息以前绕过了门控——`file_task.ingest()` 内部
直接 `reply_text`，所以总开关关掉后对方发个 PDF，机器人照样回「这个格式我只能读不能改」。
现在的判定在插件侧、下载之前：

```
may_reply = globalEnabled and (enabled or sender_id == ownerSenderId)
```

- 总开关关掉 → **谁的都不看、不存、不回**（连 Dify 都不调）；
- 总开关开着、只是会话开关关掉 → 别人发文件不回，**主人自己**发文件仍处理
  （那是主人在用工具，不是替主人说话）；
- 拿不到 `ownerSenderId`（未绑定 / Spring 不通）→ 一律按「不是主人」处理（fail closed）。

**为什么计 `reply_kind='CHAT'` 而不是所有 OUT**：限流只该约束「替主人闲聊」。
把备忘确认语算进来，会让一条日更通知把当天的自动回复额度全吃掉。

**索引**：`message_log` 的 `idx_chat_created (chat_id, created_at)` 同时覆盖最近消息与这一小时的 `COUNT`。

**私聊的会话键是对方 QQ，不是 `message_log.chat_id`**：私聊没有群号，插件写日志时
`chat_id` 一律为 `NULL`，`sender_id` 才是对方。所以 `kind=PRIVATE` 时，`outLastHour`
与 `recentMessages` 都按 `chat_id IS NULL AND sender_id = chatId` 过滤——
和 `chat_setting.chat_id`（私聊行存对方 QQ）用的是同一个值。
两种会话必须分开查：群号可能恰好等于某个人的 QQ，混着查会把两个会话串起来。

---

## 十五、NapCat 掉线补拉（`/api/agent/napcat`）

NapCat 掉线期间 QQ 根本没把消息推给它，任何下游 MQ 都变不出来。
真正能补的是 **NapCat 重新上线后向 QQ 回查历史**，而回查必须由插件做
（红线：Spring 不直连 NapCat）。所以这里只有两个接口：给「补哪些会话」和「补到哪了」。

> **调用时机（插件 0.2.32 起）**：每 10s 探 `get_status`（服务）与 `get_login_info`（账号），
> 恢复就绪时立即调一次；另每 300s（`napcat_backfill_sweep_s`）无条件兜底调一次。
> 边沿不能是唯一触发条件——登录过期、漏采样的重登、LangBot WS 断开时 NapCat 服务
> 一直报在线，`offline → online` 边沿根本不出现。补拉幂等，重复调用只多几次本机 HTTP。
> 这两个接口的契约本身不变。

两个接口都在 `/api/agent/**` 下，走 `X-Agent-Token` 共享通道——插件没有浏览器会话。

### 15.1 `GET /api/agent/napcat/targets` — 待补拉会话 + 游标

**Query**：`limit`（可选，默认 50，最大 200）。

**响应** `data` = 数组：

| 字段 | 类型 | 说明 |
|---|---|---|
| `chatType` | string | `GROUP` \| `PRIVATE` |
| `chatId` | long | 群号；私聊为对方 QQ |
| `chatName` | string | 群名；**私聊恒为 `null`**（补写日志时也不带，否则和实时日志长得不一样） |
| `lastMessageSeq` | long | 已补到的 `message_seq`；`0` = 从最新一页开始 |

**目标怎么挑**（顺序即优先级）：

1. 用户在设置页**开了自动回复或整理**的会话——掉线漏消息只发生在真正关心的会话里，
   撞上 50 个上限时不能被一堆闲聊群挤掉；
2. 近 7 天有 `message_log` 的会话，按最近活跃倒序。

两路合并后按 `(chatType, chatId)` 去重。为什么只取 7 天：几个月没说话的群补一遍，
除了多打几十次 QQ 接口没有收益，还更容易触发风控。

**游标为什么是 `message_seq` 而不是时间戳**：同一秒里可能有好几条消息，
时间戳既不能排序也不能做「大于游标」的截断，补拉会把整页重复写一遍。

### 15.2 `POST /api/agent/napcat/cursor` — 推进游标

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `chatType` | string | 是 | `GROUP` \| `PRIVATE` |
| `chatId` | long | 是 | 群号；私聊为对方 QQ |
| `lastMessageSeq` | long | 是 | 已经**成功写进** `message_log` 的最新 `message_seq` |

**响应** `data` = 推进后的实际游标（long）。

- **只前进不后退**：SQL 是 `GREATEST(last_message_seq, ?)`，重放一个更小的值不会把游标改小。
  改小意味着下一轮把已经补过的几百条再拉一遍。
- **必须先写日志再推进**：写一半就推进，没写进去的那批消息永远不会再补。
- 参数不合法回 HTTP 200 + `code=400`（与 `/api/agent/ops` 同一套约定）——
  插件的 HTTP 客户端只在非 2xx 时抛错，混用会让「参数写错了」被当成「后端没起来」。

**补拉范围**（在插件侧固定，不开放配置）：

| 项 | 值 |
|---|---|
| 单次会话数 | 50 |
| 每会话条数 | 200 |
| 每会话翻页 | 5 页 × 40 条 |
| 请求间隔 | 1 req/s |

**重放边界**：补回来的消息**只写 `message_log`**，不进 Dify、不触发自动回复——
拿几小时前的消息去跑语义决策只会得到一堆过时的回复。整理、夜间归纳、向量索引
都从 `message_log` 读，它们会自然吃到补回来的内容。

---

## 十六、登录认证（`/api/auth`）

单管理员会话。除 `captcha` / `login` / `qq/**` 外，所有 `/api/**` 都要求
`Authorization: Bearer <token>`；缺失或过期统一返回 HTTP 401 + `{code:401}`。
会话存 Redis `auth:session:<token>`，TTL 30 分钟，**每次校验滑动续期**。

### 16.1 图形验证码（密码登录用）

`GET /api/auth/captcha` → `{ captchaId, image }`，`image` 是 `data:image/png;base64,...`。
正文存 `auth:captcha:<id>`，TTL 2 分钟；**无论对错，校验一次即删**。

> **图片是 `createLineCaptcha(120, 40, 4, 20)` 生成的 120×40、4 位字符**。前端展示容器
> 至少要留够 120px 宽，且必须用 `object-fit: contain`（或按原比例显示）；
> 曾经用 108px 容器 + `object-fit: cover` + `overflow: hidden`，图被按高度放大后
> 左右各裁掉一截，首尾字符残缺、用户无法辨认。改动登录页样式时别把这条改回去。

### 16.2 密码登录

`POST /api/auth/login`

| 字段 | 类型 | 说明 |
|---|---|---|
| `username` | string | `app_setting.auth_username` |
| `password` | string | BCrypt 比对 |
| `captchaId` | string | 上一步拿到的 id |
| `captchaCode` | string | 4 位，大小写不敏感 |

→ `{ token, username }`。失败一律 400 + 人话原因（"验证码已过期，请刷新" / "用户名或密码错误"）。

### 16.3 QQ 授权登录（默认入口）

**身份只认 `app_setting.owner_sender_id`**（设置页「我的 QQ 号」），该键为空时回落到
`app.auth.owner-qq`（环境变量 `AUTH_OWNER_QQ`）。其他 QQ 号一律拒绝——码永远到不了别人手里。

#### `GET /api/auth/qq/status`

→ `{ bound: boolean, maskedQq: string }`。`maskedQq` 形如 `123****89`（头 3 尾 2，不足 6 位则头尾各 1）。
前端据此决定默认停在哪个页签：`bound=false` 时直接切到密码登录并提示去绑定。

#### `POST /api/auth/qq/code`

| 字段 | 类型 | 说明 |
|---|---|---|
| `qq` | string | 5–12 位数字，必须等于已绑定的主人号 |

→ `{ sent, expiresInSeconds, retryAfterSeconds }`

| 场景 | `sent` | 说明 |
|---|---|---|
| 正常发出 | `true` | `expiresInSeconds=300`，`retryAfterSeconds=60` |
| 冷却期内重发 | `false` | **不算错误**（`code=0`），`retryAfterSeconds` 为剩余秒数；不产生新码、不推送 |

错误（HTTP 400）：

| 消息 | 触发 |
|---|---|
| `QQ 号应该是 5–12 位数字` | 格式不对 |
| `还没有绑定主人 QQ 号：先用密码登录，在「设置 → 我的 QQ 号」里填上` | 两个来源都为空 |
| `这个 QQ 号不是已绑定的主人账号` | 与主人号不一致 |
| `验证码发送失败，确认 QQ 机器人在线，或改用密码登录` | 发送桥/机器人不可用；**此时已写入的码会被删除**，不会留下「查得到但收不到」的状态 |

推送内容：`【值守台】登录验证码 123456，5 分钟内有效。` + 一句防骗提示。
`delayMs=0`（登录码不做拟人延迟），`bizRef=auth-login:<qq>:<uuid>` **每次唯一**——
发送桥按 `bizRef` 幂等，复用键会让第二次登录收不到码。

#### `POST /api/auth/qq/verify`

| 字段 | 类型 | 说明 |
|---|---|---|
| `qq` | string | 同发码 |
| `code` | string | 6 位数字 |

→ `{ token, username }`，与密码登录**完全相同的会话**（`AuthService.createSession`），
前端不需要按登录方式分叉。

| 错误消息 | 触发 |
|---|---|
| `请输入收到的验证码` | `code` 为空 |
| `验证码已过期，请重新获取` | `auth:qq:code:<qq>` 不存在或已 TTL |
| `验证码不对，还可以试 N 次` | 比对失败，`N` 为剩余次数 |
| `错误次数过多，验证码已作废，请重新获取` | 第 6 次失败；同时删除码与计数 |

实现约束：

- 码一次性，校验成功即删 `code` / `attempt` / `cooldown` 三个键；
- 比对走 `MessageDigest.isEqual`（常量时间），不用 `String.equals`；
- 错误计数先 `INCR` 再比对，成功才清零——否则失败次数永远不累加；
- 每次重新发码都重置计数，避免历史失败把新码一起拖垮。

### 16.4 会话相关

| 接口 | 说明 |
|---|---|
| `POST /api/auth/logout` | 删除当前 token 的会话 |
| `GET /api/auth/me` | → `{ username }`；拦截器已放行 Agent 身份（`username="agent"`） |
| `POST /api/auth/password` | `{ oldPassword, newPassword }`；新密码 ≥ 6 位，成功即写 `auth_password_hash` |

> **前端入口（2026-11 补齐）**：网页「更多 → 系统 → 修改密码」（`/password`）调用本接口，
> 表单三项为旧密码 / 新密码 / 确认新密码，前端另拦「新旧相同」「两次不一致」。
> 成功后前端依次调 `POST /api/auth/logout` 作废当前会话、清本地 token、
> 跳 `/login?changed=1`（登录页默认切到密码栏并给出持久提示）。
> **边界**：后端只更新哈希，**不主动清除其它已签发的会话**，它们按 30 分钟 TTL 自然过期；
> 单用户内网自用下可接受，若将来要多端强踢需在此接口里遍历 `auth:session:*`。

---

## 十七、5.5：噪音过滤（规则页 + 插件前置判定）与 Token 用量账本（`/api/settings/noise-filter`、`/api/agent/noise-filter`、`/api/token-usage`）

> 鉴权：`/api/settings/noise-filter/**` 与 `/api/token-usage/**` 走登录态；
> `POST /api/agent/token-usage` 由 `X-Agent-Token` 鉴权（同 §15）。

### 17.1 `GET /api/settings/noise-filter` — 读取规则

**响应** `data`：
`{ enabled, keywords, regex, keepRegex, defaultKeywords, defaultRegex, defaultKeepRegex }`。
`default*` 是 `TuningDefaults` 的出厂值，供前端「恢复默认」，不随库里当前值变化。

### 17.2 `PUT /api/settings/noise-filter` — 保存规则

**请求体**：`{ enabled, keywords, regex, keepRegex }`（四个字段都给）。
只写 `noise_filter_enabled / noise_filter_keywords / noise_filter_regex / noise_filter_keep_regex` 四个键。
保存前编译正则、检查长度（关键词 ≤ 2000、正则 ≤ 1000）、拒绝嵌套量词（如 `(a+)+`）；
坏正则返回 `code=400`，**一个键都不写**。

### 17.3 `POST /api/settings/noise-filter/preview` — 试跑

**请求体**：`{ texts: string[], enabled?, keywords?, regex?, keepRegex? }`。
后四个字段是**草稿**：传了就按编辑框里的当前值判定，没传才用库里已保存的值；**不写库**。
最多 20 句、每句截 200 字；草稿里的坏正则同样拒绝。
**响应** `data`：`{ enabled, items: [{ text, kept, reason }] }`。`reason` 与单条判定同源
（`MessageNoiseFilter.dropReason()`），取值为 `blank` / `keyword` / `punctuation` / `repeat` / `null`；
`null` 表示保留（含命中 `keep_regex` 被放行、非文本、噪音过滤开关关闭）。

### 17.4 `POST /api/agent/token-usage` — 插件上报一笔用量

**请求体**：`{ source, task, model, promptTokens, completionTokens, totalTokens, chatId }`。
`source` 归一为 `spring|plugin`，`task` ≤ 32 字符、`model` ≤ 64；
`totalTokens` 缺失时由 prompt + completion 派生，单次上限 1e7；`model` 可空（显示「未标注」）。
**响应** `data` = 新增行 `id`。业务错误返回 **HTTP 200 + `code=400`**，与 `/api/agent/**` 约定一致。

### 17.5 `GET /api/token-usage/summary` — 汇总（Vue 只读）

**Query**：`days`（默认 7，clamp 到 90）。
**响应** `data`：
`{ days, since, calls, promptTokens, completionTokens, totalTokens, byTask, bySource, byDay, byChat }`。
`byTask` / `bySource` / `byDay` 三个分账数组元素形如 `{ task|source|day, calls, tokens }`；
MySQL 别名大小写与 `SUM` 返回的 `BigDecimal` 已在服务端归一成小写键 + 数值，前端不做二次转换。

**`byChat`：「花在哪个会话」饼图**（`TokenUsageChatVO[]`，元素 `{ chatId, label, kind, calls, tokens }`）：

| `kind` | 含义 | `chatId` |
|---|---|---|
| `chat` | 某个真实群聊（按 `token_usage.chat_id` 分组，只取消耗最大的 Top 6） | 群号 |
| `unassigned` | 账本里 `chat_id IS NULL` 的调用（问一句 / 画像抽取 / 夜间归纳 / 私聊自动回复） | `null` |
| `other` | Top 6 之外合并的小会话，`label` 形如「其它 2 个会话」 | `null` |

三条口径（已与需求确认）：

- **无 `chatId` 的调用单列一块**，不丢、不摊进群聊，保证各扇之和等于 `totalTokens`；
  账本没有 `kind` 列，私聊自动回复也传 `chatId = null`，**不同私聊对象暂时分不开**。
- **真实会话只留 Top 6**（`TuningDefaults.TOKEN_USAGE_CHART_TOP_N`），其余合并成 `other`；
  `unassigned` / `other` 都不参与 Top 6 竞争，永远各占一扇。0 token 的分组不画扇区、不占图例。
- **展示名优先级**：`chat_alias.aliases` 第一段 > `chat_setting.chat_name` 第一段 > 「群 + 群号」。
  SQL 只 JOIN `kind='GROUP'` 的命名行，避免「群号恰好等于某人 QQ」的私聊别名串进来；
  逗号串按 `utils/name.ts#firstSegment` 同源分隔符在 Java 侧切第一段，SQL 不做字符串切分。

> **后端不返回浮点占比**：只给 `tokens` / `calls`，前端按 `byChat` 之和算百分比与弧长，
> 以后改 Top N 不用动接口。数组已按 `tokens` 降序排好，颜色分配才稳定。
> 前端不引图表库，用 SVG `<circle>` + `stroke-dasharray` 画甜甜圈，圆中心放总量；
> 图例复用全局 `.rows` / `.row` 卡片，`hoveredSlice` 联动扇形与图例。
> `unassigned` 用冷灰、`other` 用最浅的暖褐，真实群聊走纸墨色系，三者在浅色纸底上都不可混。

### 17.6 `GET /api/token-usage/recent` — 最近明细（Vue 只读）

**Query**：`days`（默认 7，最大 90）、`limit`（默认 50，最大 200）。
**响应** `data` = 行数组：
`{ id, source, task, model, promptTokens, completionTokens, totalTokens, chatId, createdAt }`。

> **账本边界（§17.4–17.6）**：只追加、只读展示，没有删除 / 清空接口，也不参与任何业务判定。

> **前端展示约定（2026-11 调整）**：接口仍按 `limit=50` 一次拉齐，但 `/usage` 页面
> 默认只渲染 12 条，「展开全部 / 收起」作为列表卡片的最后一行；否则 50 行明细会把上方的
> 分账与按天图表挤出首屏。按天柱状图单日时柱子限宽 48px，不能靠 flex-grow 撑成整宽色块。
> 明细列表**必须复用 `style.css` 的全局 `.rows` / `.row`**：`.rows` 自带卡片（发丝线 +
> 圆角 + 纸底）与 `li + li` 行间线，`.row` 自带 `padding: 13px 16px`；页面 scoped 样式
> 只允许调整行高 / 光标，**不得把横向 padding 改成 0 或再叠 `border-bottom`**，否则文字
> 会贴到卡片边框、分隔线变粗（两轮截图反馈的根因）。右侧 tokens 做成「数字 + `tokens` 单位」
> 的右对齐小统计块，整体垂直居中，避免单个数字飘在标题与副行之间。
> §17.5 的会话占比图例**同样复用这套 `.rows` / `.row`**，只加一颗色块，不另做卡片样式。
> Dify Workflow 的 blocking 响应只给 `data.total_tokens`，不拆 prompt/completion、不回传模型名；
> 只有 chat-messages 的 `metadata.usage` 才有拆分。缺的数据记 0 / NULL /「未标注」，不猜。
> 记账失败只 warn（表没迁移、库被锁都不影响调用），一条消息最多记一行。

### 17.7 `POST /api/agent/noise-filter/check` — 插件前置噪音判定

**调用方**：LangBot 插件（每条文本消息，在写 IN 日志之后、`triggered` 判断之前）。
**鉴权**：`/api/agent/**` 的 `X-Agent-Token`（同 §15），网页登录态不可调。

**请求体**：`{ text, msgType }`。规则**不从插件传**，Spring 一律读 `app_setting` 里
四个 `noise_filter_*` 键，与整理 / 夜间归纳的 `filter()`、规则页的 `preview()`
共用同一条 `MessageNoiseFilter` 链（§17.1–17.3）。

**响应** `data` = `{ noise: boolean, reason: string }`；保留时 `reason` 为空串
（不用 `null`，避免 `Map.of` / 前端判空分歧）。`reason` 取值同 §17.3：
`blank` / `keyword` / `punctuation` / `repeat`。

**约定**：

- 非文本消息（`msgType != "text"`）一律判为保留；空文本判 `blank`；
  命中 `keep_regex` 或含主人别名时优先放行。
- **噪音过滤开关**（`noise_filter_enabled`）关闭时全部判为保留。它和自动回复总开关
  （`reply_enabled`）是两回事：后者管「发不发言」，前者管「要不要进模型」，互不影响。
- 命中噪音的消息**原文照常写 `message_log`**，只是不再进 Dify、不再自动回复。
  它和「自动回复总开关关闭」不是一回事：总开关是不发言，这里是连模型都不调。
- 插件侧 **fail-open**：接口异常 / 超时 / Spring 还是旧版没有该端点 / 响应为空，
  一律当「非噪音」放行。判定失败最坏只是多处理一句废话，绝不能吞掉主人的消息。
- 每条消息多一次 HTTP 判定，换来「网页改完立刻生效」与「单一规则链」：
  避免 Java / Python 正则方言不一致（如 `\p{P}`），也避免插件缓存旧规则。
- 原 `GET /api/collection-tasks/rules` 与 `/api/intercept-rules` CRUD 已删除，
  见 §6.3 / §七。

---

## 十八、待 Phase 4 确认的集成点

- **Dify 应用配置 API**：`POST /api/style-profile/update` 后更新画像 Prompt 的调用方式、鉴权、字段名。
- **NapCat 文件收发 API**：Phase 3 随 `send_file` 一并确认。
- **Dify 侧 Tool 定义**：Dify 调上述 Spring Boot API 的 Tool 参数 schema，Phase 4 编写。
