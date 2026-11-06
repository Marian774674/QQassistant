# 值守台 · 项目设计文档（简版）

> 面向汇报与快速了解的概览。字段级表结构、接口逐字段契约、Dify 决策信封等细节见
> [`database_schema.md`](./database_schema.md)、[`api_contract.md`](./api_contract.md)、[`agent_contract.md`](./agent_contract.md)。

---

## 一、项目背景

### 1.1 要解决的问题

QQ 是日常信息的主要入口，但信息是**被动出现**的：通知看完就沉底、带时间的事靠脑子记必然漏、
离开半天回来要爬几百条楼、想找一句话只记得大概意思。与此同时，待办类工具都要求人主动打开、
主动录入——多这一步，就少记一次。

本项目把 **QQ 本身当成入口**：机器人待在群和私聊里，用大模型理解语义与意图，
用工程代码管理状态、持久化与调度。

### 1.2 项目定位

一个**跑在自己机器上、单用户、内网使用**的私人 QQ 助手：

- **记住该记住的**——从聊天里抽备忘录，按重要性分级提醒；
- **替你看着群**——你不在时群里发生了什么，打开网页就知道；
- **帮你翻回去**——一句话提问，答案附原文证据；
- **替你说该说的话**——按本人语气自动回复，拿不准就降级成草稿。

### 1.3 设计原则

| 原则 | 含义 |
|---|---|
| **AI 做语义，代码管状态** | 大模型只负责意图理解、语义生成与「发不发 / 填什么」的决策；状态机、阈值、持久化、定时任务全部在 Spring Boot |
| **一切 AI 产出可降级** | 回复、文件、备忘、事实四类产出都可能出错，统一降级为草稿交人工确认 |
| **证据可回溯** | 整理与查询的每条结论都必须带 `source_msg_ids` / `used_ids`，对不上原始消息就丢弃或降级 |
| **副作用幂等** | 发送、防重、日志写入均有幂等键，重复投递 / 重复确认 / 重启都不会重复产生副作用 |
| **宁可不回，不发错** | Dify 返回非法决策信封时一律按 `SILENT` 处理，绝不把模型原文直接发到 QQ |

### 1.4 范围与非目标

**范围内**：单主人；QQ 私聊与群聊；Web 管理端；MySQL 单库；Docker Compose 单机部署。

**明确不做**：多用户 / 多租户与鉴权；QQ 协议自研；移动端 App；公网部署与水平扩展；
主动插话式自动回复；文件表格自动填写（已冻结，代码保留不迭代）。

---

## 二、功能列表

### 2.1 核心功能（F1–F4）

| 编号 | 功能 | 说明 | 状态 |
|---|---|---|---|
| **F1** | 备忘与分级提醒 | 从消息中抽取 `title / dueTime / importance(1-5) / category`，按级别展开多条提醒；QQ 回「完成 / 取消」即闭环；每次改动留痕 | ✅ 可用 |
| **F2** | 拟人自动回复 | 私聊按公式延迟发送，语气来自事实记忆 + 风格画像；群聊仅 @ 或前缀触发；高风险回复降级为草稿 | ✅ 可用 |
| **F3** | 消息整理 | 每小时把勾选的群整理成要点，按重要度排序；打开首页时补充整理并展示「你不在的这段时间」 | ✅ 可用 |
| **F4** | 历史查询 | 一句话提问，跨消息 / 文件 / 备忘 / 事实回答；认不出「小明」是谁时反问；答案附原文证据 | ✅ 可用 |

### 2.2 支撑功能

| 功能 | 说明 |
|---|---|
| **统一草稿中心** | `REPLY / FILE / MEMO / FACT` 四类草稿共用一张表；确认后按类型分发（发送 QQ 或写业务表）；`PENDING` 永不过期 |
| **统一消息日志** | 所有进出消息落 `message_log`，是整理、归纳、查询、限流、排查的唯一事实源 |
| **主动发送** | Spring Boot 经独立发送桥向 QQ 推送文本 / 文件，带拟人延迟与幂等 |
| **群会话设置** | 每个群 / 私聊的自动回复开关、纳入整理开关、聊天别名 |
| **噪音过滤** | 取代原「拦截规则」：规则页编辑四个 `noise_filter_*`，插件每条文本消息实时问 Spring；命中只留原文、不进 Dify、不回复 |
| **记忆管理** | 事实记忆 CRUD、风格画像查看与手动编辑、定期总结（每日/每周 + 启动补做）、总结频率设置 |
| **消息检索** | 七路召回 + RRF 融合 + 证据校验的历史查询链路 |
| **文件任务（冻结）** | xlsx / docx / pdf 的结构化读写与回传，作为历史能力保留 |

---

## 三、功能模块

### 3.1 总体架构

```
QQ 客户端
   │ QQ 协议
NapCat
   │ 事件
LangBot + qq_assistant 插件 ──► Dify Chatflow / Workflow
   │  事件归一化、触发判定、噪音过滤、  │  意图识别、对话状态、语义生成、
   │  拟人延迟、文件 IO、动作执行       │  决策信封（发不发 / 填什么）
   │                                   │
   └──────────────► Spring Boot ◄──────┘
                       │  状态机、持久化、调度、草稿中心、画像
                       ├──► MySQL 8（21 张表）
                       └──► Vue 3（11 个页面）

Spring Boot ──► send_bridge ──► LangBot / NapCat（主动发文本、发文件）
```

### 3.2 分层模块划分

| 层 | 模块 | 主要职责 | 技术 |
|---|---|---|---|
| **接入层** | NapCat | QQ 协议、收发消息与文件 | Docker |
| | LangBot 插件 | 事件归一化、群触发判定、前置噪音判定（调 Spring）、拟人延迟、文件解析 / 回写、决策动作执行、幂等 | Python 3.12 |
| | 发送桥 `send_bridge` | 把「发文本 / 发文件」封装为幂等 HTTP 接口，拟人延迟，文件通道回落 NapCat | Python + FastAPI |
| **智能层** | Dify Chatflow | 对话意图、会话状态、生成决策信封 | Dify |
| | Dify Workflow「记忆与整理」 | `digest`（整理）/ `extract`（归纳）/ `ask`（查询三阶段） | Dify |
| **业务层** | Spring Boot | 21 张表持久化、提醒调度、草稿分发、画像与检索 | Java 21 / Spring Boot 3.4 |
| **展示层** | Vue 3 | 首页、备忘、草稿、问一句、记忆、设置等 11 页 | Vue 3 + TS + Vite |
| **数据层** | MySQL 8 | 消息、备忘、草稿、记忆、整理、配置 | MySQL |

### 3.3 后端功能模块

| 模块 | 关键控制器 | 关键服务 | 说明 |
|---|---|---|---|
| 消息与会话 | `MessageController`、`ConversationController` | `MessageLogService`、`ConversationSessionService` | 消息日志写入 / 查询；QQ 会话 ↔ Dify `conversation_id` 映射 |
| 备忘 | `MemoController` | `MemoService`、`ReminderPlanner` | 新建 / 编辑 / 完成 / 取消、提醒计划重算、变更留痕、提醒预览 |
| 草稿 | `DraftController` | `DraftService` | 四类草稿统一存取；确认时按类型分发 |
| 群与规则 | `ChatSettingController`、`CollectionTaskController`、`AgentNoiseFilterController`、`AppSettingController` | `ChatSettingService`、`MessageNoiseFilter`、`NoiseFilterSettingsService` | 会话开关、噪音过滤规则与单条判定；`collection_task` 端点仅兼容保留 |
| 画像与记忆 | `ProfileController`、`StyleProfileController`、`ContactController`、`MemoryController` | `ProfileFactService`、`StyleProfileService`、`ContactService`、`MemoryJobService` | 事实 kv、风格画像、人物别名、夜间归纳与重建 |
| 消息整理 | `DigestController` | `ChatDigestService`、`DigestRefreshService` | 每小时整理、进首页补整理、已读锚点 |
| 历史查询 | `AskController` | `AskService`、`RetrievalService`、`RrfFusion` | 查询理解、七路召回、融合截断、作答校验 |
| 检索索引 | `MemoryController` | `EmbeddingIndex`、`EmbeddingJobService` | 向量索引重建与缺口统计 |
| 文件任务 | `FileTaskController` | `FileTaskService` | 插件状态机的只读镜像（功能冻结） |
| 配置与操作 | `AppSettingController`、`AgentOpsController` | `AppSettingService`、`AgentOpService` | 全局配置；Dify 的窄写操作入口（带令牌） |
| 调度 | `schedule/` | `MemoReminderScheduler`、`DigestScheduler`、`MemoryScheduler` | 提醒扫描（60s）、每小时整理、每日 0:10 归纳 |

### 3.4 数据模型（21 张表）

| 域 | 表 |
|---|---|
| 消息与会话 | `message_log`、`conversation_session`、`napcat_cursor` |
| 备忘 | `memo`、`memo_reminder`、`memo_change_log` |
| 草稿 | `draft` |
| 群与规则 | `chat_setting`、`chat_alias`；`intercept_rule` / `collection_task` 为历史保留表（拦截规则已废弃，运行时不再读写） |
| 记忆 | `profile_fact`、`style_profile`、`contact`、`memory_note` |
| 整理 | `chat_digest_item` |
| 文件 | `file_task`（只读镜像） |
| 配置与检索 | `app_setting`、`ask_trace`、`message_embedding` |
| 用量账本 | `token_usage`（只读统计，5.5 新增） |

### 3.5 组件职责红线

| 红线 | 说明 |
|---|---|
| 业务逻辑不进 Dify 提示词 | 阈值、提醒策略、状态迁移只写在 Java 服务层 |
| Dify 不接触文件流 | 插件解析成结构再送 Dify，Dify 只回「填什么到哪」 |
| 风格画像不实时改写 | 只有定期总结能更新；事实记忆才可实时写 |
| Spring Boot 不直连 NapCat | 一切 QQ 动作经插件或发送桥 |

---

## 四、工作流介绍

### 4.1 消息接入与自动回复

```
QQ 消息
  → NapCat → 插件事件监听
  → 消息去重（dedupe_key）→ 写 message_log（含昵称 / 群名）
  → 群聊触发判定：仅 @机器人 或前缀消息继续；其余到此结束
  → 前置噪音判定：POST /api/agent/noise-filter/check（规则只在 Spring 侧）
        ├─ 命中 → 原文已入 message_log，但不进 Dify、不回复，到此结束
        └─ 未命中 / 判定失败（fail-open）→ 继续
  → 取画像 / 未终结备忘 / 自动回复上下文（开关、限流、子提示词、最近消息）
  → Dify Chatflow → 返回 JSON 决策信封
  → 插件按 action 机械分发：
        SEND        → 私聊拟人延迟后发送（1.5s + 字数×0.15s + 抖动，封顶 15s）
        DRAFT       → 写入统一草稿中心，等人工确认
        SILENT      → 只记日志
        MEMO        → 调 Spring 建备忘
        MEMO_CLOSE  → 调 Spring 关闭备忘
        FACT        → 调 Spring 写事实 / 建事实草稿
        ASK / WAIT_FILE / FILE_FILL → 反问、等文件、文件填写
  → 发送结果写 message_log（direction=OUT，reply_kind 区分 TASK / CHAT）
```

**自动回复开口条件**：会话开关 `auto_reply_enabled` 与全局紧急闸 `reply_enabled` 同时开启；
仅对 `CHAT` 类应答限流（每会话每小时默认 20 条）。高风险回复（替人承诺、定时间、谈钱、
涉及第三方评价）由 Dify 标记 `needs_confirm`，降级为 REPLY 草稿。

**两层开关的语义不同**：

| 开关 | 关掉之后 |
|---|---|
| 会话开关（`chat_setting.auto_reply_enabled`） | 这个群/私聊不再替主人闲聊，但 `TASK` 仍要发——备忘确认语照旧，否则等于把群里的备忘功能一起关了 |
| 总开关（`app_setting.reply_enabled`） | **彻底一个字都不说**：`CHAT`、`TASK`、文件任务应答全停。动作照跑（备忘照建、状态照改、文件照写），只是不往外发 |

因为这两层不能混，`reply-context` 把总开关单独放在 `globalEnabled` 里下发；
`enabled` 仍是三层相乘（总开关 AND 会话开关 AND 令牌桶），只用来管 `CHAT`。
落地方式是在插件最底层压 `reply_text`（`core.reply.silence()`）——直接回话的点散在
`file_task` / `dispatch` 等分支里七八处，逐个判断必然漏。
（原「拦截回复」分支已随拦截规则整体下线，见 §七 / §5.5。）

**文件消息的例外**：文件任务以前绕过了门控（`file_task.ingest()` 内部直接回话），
所以总开关关掉后对方发个 PDF 仍会收到「这个格式我只能读不能改」。现在在下载之前就判一次：

```
may_reply = globalEnabled and (enabled or sender_id == ownerSenderId)
```

总开关关掉 → 谁的都不看、不存、不回；总开关开着而只是会话开关关掉 → 别人发文件不回，
**主人自己**发文件仍处理（那是主人在用工具，不是替主人说话）。`ownerSenderId` 由
`reply-context` 一并下发，未绑定或 Spring 不通时按「不是主人」处理。

### 4.2 备忘抽取与分级提醒

```
消息 → Dify 识别邀约 / 通知
  → 输出 title、dueTime、importance(1-5)、category
  → 高置信：直接写入 memo
  → 低置信：写入 MEMO 草稿，人工确认后入库
  → Spring 按重要性展开 memo_reminder：
        5 级：24h / 2h / 30min 三次强提醒
        4 级：24h / 1h 两次较强提醒
        3 级：2h 一次温和提醒
        2 级：当天一次轻提醒
        1 级：静默存档，不提醒
  → 调度器每 60s 扫描到期提醒 → 发送桥推送 QQ
  → 用户 QQ 回「完成 / 取消」→ Dify 输出 MEMO_CLOSE → Spring 更新状态
  → 或在 Vue 上完成 / 取消；超过 dueTime 未收尾 → OVERDUE
```

状态机：`ACTIVE → REMINDED → AWAITING_REPLY → DONE | CANCELLED`，另有 `OVERDUE`。
每条提醒以「备忘 id + 提醒 id」独立幂等，多档提醒不会互相吞掉。

### 4.3 消息整理（F3）

```
每小时第 5 分钟（或用户打开首页时按阈值补跑）
  → 取上一个整点窗口内、已勾选「纳入整理」的群消息（私聊不整理）
  → 噪音预过滤：丢弃 666 / 哈哈哈 / 纯标点 / 重复字符；@我、时间词、文件、主人别名保留
  → Dify Workflow task=digest：输出 items[]（摘要 + importance + keywords + source_msg_ids）
  → Spring 硬校验 source_msg_ids 必须存在于输入消息，对不上整项丢弃
  → 按 (chat_id, period_start) 幂等写入 chat_digest_item
  → 首页按重要度倒序展示；importance >= 4 时弹窗一次
  → 用户点「都看过了」→ 更新 last_digest_seen
```

摘要要求「谁说了什么」，不允许出现原文没有的信息；每条都能点回原始消息。

### 4.4 历史查询（F4）

```
用户提问（Vue「问一句」页）
  1. 查询理解：Dify ask / stage=resolve 抽实体、关键词、改写查询、时间区间
     Spring 查 contact 认人：0 条 = 不认识，≥2 条 = 冲突，均转反问；确认后写 contact(source=USER)
  2. 检索前补跑向量索引，缺口如实写入 scope.lag
  3. 七路召回（Spring）：
     note / msg-person / msg-mention / msg-lexical / msg-semantic / memo / file
     单路失败只丢这一路，不整体报错
  4. RRF 融合去重 + 时间软分段 + 30000 字符 / 150 条预算截断
  5. 作答：Dify ask / stage=answer → 结论 + used_ids
  6. 校验：used_ids 必须是候选集子集，否则 degraded，只给证据不给结论
  7. 证据不足且有轮次预算 → Dify ask / stage=expand 换检索串，最多 3 轮
  8. 仍无结果 → 终态「没找到」；候选为空也先 expand 一次，避免措辞问题误判
```

反问是**无状态**的：前端保存上一轮问题与候选，用户选人后带 `clarify` 字段重发；
证据 id 带类型前缀（`msg:` / `note:` / `file:` / `memo:`），避免四张表自增 id 撞车。

### 4.5 夜间归纳与记忆

```
每天 0:10（默认关闭，app_setting.memory_job_enabled 开启后生效）
  → 取前一天新增消息 → 噪音预过滤（全噪音批次跳过 Dify 并推进游标）
  → Dify Workflow task=extract
  → 抽出 contacts[]（新认识的人）与 notes[]（事实三元组 + 证据 id）
  → contact：UNIQUE(sender_id, alias) 去重；source=USER 的行永不被自动任务覆盖
  → memory_note：谓词受控词表，按 (主体, 谓词, 值) 去重
  → 首次启用可调 POST /api/memory/rebuild 全量补算

事实记忆（实时）：明确「我本人」的高置信信息 → profile_fact 直接写；改口直接覆盖；
                低置信 → FACT 草稿；用户可在记忆页手动增删改（最终兜底）
风格记忆（定期）：message_log → 定期总结 → style_profile 新版本 → 注入画像 Prompt
                禁止单条消息实时改写，防止 System Prompt 漂移
```

### 4.6 草稿确认与主动发送

```
AI 产出（回复 / 文件 / 备忘 / 事实）
  → 置信度不足时写入 draft（PENDING，永不过期）
  → Vue 草稿中心按类型分组展示
  → 用户确认 POST /api/drafts/{id}/confirm：
        REPLY / FILE → Spring 调发送桥 → LangBot / NapCat 发送
        MEMO / FACT  → Spring 直接写入对应业务表
  → 用户拒绝 → REJECTED

主动发送（提醒 / 草稿确认）：
Spring Boot → send_bridge（:5301）→ LangBot 发文本 / 发文件
  → bizRef 幂等：在途返回 409，重试返回首次 messageId，状态落盘重启不丢
  → 文本与文件是两份独立契约，永不混用；文件通道不支持时回落 NapCat
```

---

## 五、后续优化

> 本章记录已经讨论过的优化方向、当前决策和触发条件。
> 原则：**不为用技术而用技术**——单用户、单机、内网场景下，能用一个进程解决的问题不引入第二个中间件；
> 只有数据量、并发量或多实例需求真正出现时才升级。

### 5.1 已完成：Spring 侧噪音预过滤（2026-11）

| 优化 | 做法 | 效果 |
|---|---|---|
| **整理 / 归纳前的噪音过滤** | 新增 `MessageNoiseFilter`，在 `digest` / `extract` 组装模型输入前过滤「666 / 哈哈哈 / 笑死我了 / 纯标点 / 重复字符」；`ChatDigestService` 返回 `DigestOutcome(saved, noiseOnly)`，进首页补整理遇到全噪音窗口跳过 Dify 并推进 `last_digest_run`；夜间归纳全噪音批次跳过 Dify 但推进游标 | 不碰插件、不改表结构，直接减少整理/归纳的无效 token；原文仍完整保留在 `message_log` |
| **过滤规则配置化** | `noise_filter_enabled / noise_filter_keywords / noise_filter_regex / noise_filter_keep_regex` 四个 `app_setting` 键 | 可热改，不需要重新部署 |
| **保留信号硬约束** | @、问号、文件/图片、时间与事务词、主人昵称/QQ 号一律保留 | 宁可多送，不误伤真正有事的信息 |

> 没有使用 Spring MVC `HandlerInterceptor`：插件是直接调 Dify 的，HTTP 拦截器看不到那条链路；
> 真正能省 token 的位置是 Spring 组装 LLM 输入的地方。

### 5.2 必做：登录 / 验证码 / Redis Session（课程要求）

> 状态（2026-11）：后端 + 登录页已实现，待启动 Redis 联调。首次登录前设置 `AUTH_INITIAL_PASSWORD`；
> 不设置则启动日志会打印随机临时密码。
>
> 2026-11 追加：**QQ 授权登录已实现**（机器人私聊发码），成为默认登录方式；
> 密码 + 图形验证码降为兜底入口。

#### 为什么不用 QQ 互联 OAuth

标准 QQ 授权登录（`graph.qq.com`）有两条硬门槛：① 开发者实名认证（18 岁以上）；
② **回调域名必须已 ICP 备案，且备案主体与申请主体一致**。本项目跑在内网 `localhost:5173`，
既没有备案域名，回调也不能填内网地址（还得额外做内网穿透）。为一个单用户自用系统去备案域名，
投入产出完全不成立。

替代方案利用的是已有基础设施：**机器人本来就在 QQ 里在线**，让它在私聊里发一条一次性验证码即可。
身份校验强度与「授权登录」等价——码只会到号主手里；零外部依赖、无需审核、内网直接可用，
也避免再引入一个身份提供商。

#### QQ 授权登录（默认）

| 项 | 设计 |
|---|---|
| 身份来源 | `app_setting.owner_sender_id`（设置页「我的 QQ 号」），为空时回落到 `AUTH_OWNER_QQ` |
| 发码 | `POST /api/auth/qq/code` → 6 位数字存 `auth:qq:code:<qq>`，TTL 5 分钟 |
| 推送 | 经 `LangBotClient` 走发送桥私聊发给主人号，`delayMs=0`（登录码不做拟人延迟） |
| 幂等 | `bizRef = auth-login:<qq>:<uuid>` **每次唯一**——发送桥按 bizRef 幂等，复用键会让第二次登录收不到码 |
| 冷却 | `auth:qq:cooldown:<qq>` 60 秒；冷却期内返回 `sent=false` + 剩余秒数，不算错误 |
| 校验 | `POST /api/auth/qq/verify`；一次性、常量时间比对，连错 5 次作废当前码（爆破空间 5/10⁶） |
| 会话 | 与密码登录共用 `AuthService.createSession`，签发同一个 Redis 会话，前端不分叉 |
| 兜底 | 机器人掉线时发不出码 → 登录页保留「密码登录」页签；未绑定主人号时自动切到密码页并提示 |

#### 密码登录（兜底）

| 项 | 设计 |
|---|---|
| 验证码 | `GET /api/auth/captcha` 生成图形验证码，Redis 存 `auth:captcha:<id>`，TTL 2 分钟，登录时一次性删除 |
| 登录 | `POST /api/auth/login` 校验验证码 → 校验用户名/密码（BCrypt）→ 生成 token，Redis 存 `auth:session:<token>`，TTL 30 分钟 |
| 会话校验 | `AuthInterceptor` 拦截 `/api/**`，放行 `/api/auth/captcha`、`/api/auth/login`、`/api/auth/qq/**`、`/api/agent/**`、`/api/health`；每次请求滑动续期，失效返回 401 |
| 账号 | 单管理员：`app_setting.auth_username` + `auth_password_hash`，首次启动写默认值并提示修改；不做多用户/角色 |
| 前端 | `/login` 页分「QQ 登录 / 密码登录」两栏；路由守卫无 token 跳登录；`http.ts` 统一带 `Authorization: Bearer`，401 清 token 回登录 |

> 如果课程验收明确要求「多用户 / 注册」，再把账号迁到 `sys_user` 表；当前单管理员足够演示完整的登录链路。
> 真要接标准 QQ 互联 OAuth，等有了备案域名再做——接口形状已经预留（换 provider 即可），不需要动会话层。

### 5.3 必做：接口限流（Redis + Lua 令牌桶）

> 状态（2026-11）：`/api/**` 过滤器和 `reply-context` 群聊桶已实现，待 Redis 联调。
> 插件 Dify 前置跳过尚未做（见下面第 2 条的取舍）。

限流分两层，缺一不可：

1. **Spring API 层**：`RateLimitFilter` 对 `/api/**` 按用户/IP 做令牌桶，Lua 脚本原子扣减；超限返回 429。
   保护登录、草稿确认、设置等写接口。
2. **QQ 消息层（防刷屏的关键）**：在 `reply-context` 里按会话做令牌桶；桶空时返回 `enabled=false`。
   插件在 Dify **之后**用 `reply_gate` 检查 `enabled`，所以**不改插件**就能做到「刷屏时不发回复」。
   但这次 Dify 调用已经发生，token 仍然消耗；要连 token 一起省，需要在插件里 Dify 之前加一个
   `if not reply_ctx.get("enabled") and not is_task: return`（约 3 行），这和「尽量不动插件」有取舍。
   原 `reply_rate_limit`（每小时条数）保留为粗粒度兜底；令牌桶负责平滑突发（例如 10 秒内 20 条）。

> Spring MVC 拦截器只能挡 HTTP 请求，挡不住插件直接调 Dify。真正防群聊刷屏的点在 `reply-context`；
> API 限流是另一条线，两者都要有。

### 5.4 已完成：NapCat 掉线补拉（消息队列暂缓）

> 状态（2026-11）：**已实现**。插件 `core/backfill.py` + `core/napcat.py`，
> Spring `NapcatCursorService` + `/api/agent/napcat/{targets,cursor}`，新表 `napcat_cursor`。
> Redis Stream / 独立 MQ 仍暂缓——它们解决不了「NapCat 掉线期间 QQ 没推消息」这件事。

**边界**：NapCat 掉线期间 QQ 根本没把消息推给它，任何下游 MQ 都变不出来。
MQ 只能保证「已到达 NapCat / LangBot 的消息」在消费端重启或变慢时不丢——这是另一个问题，
当前单机单消费者没有出现，所以先不做。真正要补的是 NapCat 重新上线后向 QQ 回查历史。

| 项 | 设计 |
|---|---|
| 触发 | 插件后台循环每 10s 同时调 NapCat `get_status`（OneBot 服务）与 `get_login_info`（QQ 账号登录态）；两者都就绪且「从没补过 / 上次不就绪 / 探活失败后恢复」时立即补一次。**边沿只是加速信号**：登录过期、被漏采样的重登、LangBot WS 断开都不会产生 `offline → online` 边沿，所以另加 `napcat_backfill_sweep_s`（默认 300s）的**周期兜底补拉**。补拉幂等，重复跑只多几次本机 HTTP；抛异常也要等下个周期再试，不能每轮探活全量扫历史 |
| 范围 | Spring 提供 `GET /api/agent/napcat/targets`：近 7 天有 `message_log` 或已开启自动回复 / 整理的会话 + 各自游标；单次最多 50 个会话、每会话最多 200 条、每会话最多翻 5 页，避免触发 QQ 风控 |
| 游标 | 每会话存 `last_message_seq`（NapCat 的 `message_seq`，不是时间戳）；整页成功写入 Spring 后才推进，失败不推进，下一轮重试 |
| 拉取 | 从 `message_seq=0` 取最新一页，按 `message_seq` 向前翻页，直到 `<= 游标`；页内按 seq 升序重放 |
| 去重 | 实时与补拉统一 `dedupeKey = "msg:" + message_id`，由 `message_log.uk_dedupe` 吞掉重叠。实时路径当前用随机 uuid，必须先改成稳定键 |
| 重放边界 | 补拉消息**只写 `message_log`**，不进 Dify、不触发自动回复；整理 / 夜间归纳 / 向量索引都从 `message_log` 读，补回来的消息会自然进入后续流程 |
| 游标存储 | Spring 侧新增 `napcat_cursor(chat_type, chat_id, last_message_seq, updated_at)`；插件经 `POST /api/agent/napcat/cursor` 推进游标，不落本地文件，容器重建不丢 |
| 失败处理 | 单会话失败只记日志，其它会话继续；补拉请求限速 1 req/s，不阻塞实时消息 |

**实现时补上的几条（原设计没写，但不做会出错）**：

| 问题 | 处理 |
|---|---|
| 历史里混着**机器人自己说的话** | 按 `sender_id == bot_qq` 跳过。当成 `IN` 写进去，检索会把机器人的话当成对方说的证据，整理也会把机器人回复算进「今天群里聊了什么」 |
| 补拉消息的 `created_at` 落成「现在」 | `MessageLogItem` 新增 `createdAtEpochSeconds`，后端按 JVM 时区换算。否则一次补回两百条历史会全挤在同一个时刻，整理时间窗与夜间归纳批次全部失真 |
| **探活异常被当成「还是在线」** | NapCat 重启 / 退出登录期间 HTTP 是关的，`get_status` 会抛异常。异常冒出去的话 `_was_online` 一直停在 `True`，回来后 `online and not True` 恒为 `False`——**永远不触发补拉**。所以 `tick()` 把探活失败一律当离线 |
| 轮询间隔 30s 抓不住重启窗口 | NapCat 重启只要十几秒，30s 一次有 2/3 概率整个窗口都没探到。`get_status` 是**本机** HTTP、几乎零成本，间隔改为 10s |
| **登录过期 / WS 断开时根本没有「离线」边沿** | 0.2.31 及更早只靠 `online` 由 false 变 true 触发。NapCat 进程和 HTTP 服务在登录过期时都还在，`get_status` 可能照常报在线；重登也可能落在两次 10s 探活之间；LangBot 与 NapCat 的 WS 断开时 NapCat 完全正常。这三种情况 `_was_online` 一直停在 `True`，补拉永不执行——「重新登录后中间消息还是没被消费」的根因。0.2.32 起：单独探 `get_login_info` 抓账号登录态恢复，再加 300s 周期兜底，不再依赖任何边沿 |
| `get_status` 不返回 `online` 字段 | 兼容实现可能只给 `good`、甚至只给版本信息。字段缺失时只要 data 非空就当在线，否则 `_was_online` 永远 `False`、补拉同样永不触发 |

> 为什么放插件：红线要求 Spring 不直连 NapCat，插件本来就有 `napcat_api_url` 和鉴权配置；
> 补拉属于「收发层」，Spring 只负责目标和游标。
> 代价是插件新增 `core/backfill.py` 和启动 / 重连两个 hook，不改现有消息处理主流程。
>
> 注意：LangBot 的事件只暴露消息事件，没有「机器人上线」事件，所以只能轮询
> `get_status`（服务）+ `get_login_info`（账号）。后者才是「QQ 是否真的登录着」的信号——
> 登录过期时服务还活着，但消息已经不往 LangBot 推了。
> 补拉期间到达的实时消息与补拉页重叠是正常现象，靠 `message_id` 去重。
>
> 为什么不用 LangBot 的平台 API：OneBot11 适配器的白名单（`PLATFORM_API_MAP`）里
> 没有历史消息接口，取历史只能直连 NapCat——这也是补拉必须放插件的原因之一。

### 5.5 已完成：近期收益项（2026-11）

> 状态：**四项全部落地**；随后「噪音过滤取代原拦截规则」也已落地。
> 全量回归：Java 454 / 454（61 类）、插件 215 / 215、发送桥 31 / 31、Vue `npm run build` 通过。
> 新增表 `token_usage`，总表数 20 → 21；接口见 [`api_contract.md`](./api_contract.md) §17。
> 随后又给 `/usage` 补了「花在哪个会话」占比饼图（见下表与 §17.5），不改表、不新增端点。

| 项 | 落地方式 | 边界 |
|---|---|---|
| 噪音过滤取代原拦截规则 | 原 `/api/intercept-rules` CRUD、`GET /api/collection-tasks/rules`、插件规则缓存与模板回复**全部删除**；前端 `/rules` 改为「噪音过滤」页（设置页不再放此分区），四个 `noise_filter_*` 键经 `GET/PUT /api/settings/noise-filter` + `POST .../preview` 编辑；插件在 IN 日志之后、`triggered` 判断之前调 `POST /api/agent/noise-filter/check` | 命中**只留原文、不进 Dify、不回复**，与「总开关关闭」语义不同；规则只在 Spring 存一份，插件不缓存、不做本地正则匹配；判定失败一律 **fail-open** 放行；每条文本消息多一次 HTTP 判定，换来规则即时生效与单一判定链。试跑支持**草稿规则**（改完不保存也能看判定），草稿与保存都先编译校验、限长、拒绝嵌套量词；`TuningDefaults` 提供出厂值，可一键恢复默认。两张旧表保留不 DROP |
| Token 用量账本 | 新表 `token_usage`。Spring 侧 `DifyWorkflowClient` 读 Workflow `data.total_tokens` 记 `source=spring`；插件 `core/usage.py` 读 chat-messages `metadata.usage` 记 `source=plugin`；Vue `/usage` 只读展示 7/30/90 天汇总、按任务 / 来源 / 天分账、**「花在哪个会话」占比饼图**与最近明细（默认铺 12 条，可展开到拉回的 50 条；列表与图例复用全局 `.rows` / `.row` 卡片，保留 16px 横向内边距，展开按钮作为卡片末行） | **纯旁路**：记账失败只 warn，绝不回压消息 / 整理 / 问答。Workflow 只给总量、不拆输入输出也不回传模型名 → 拆账列记 0、模型显示「未标注」，不猜。饼图按 `chat_id` 分组，无群号的调用单列「未归属会话」保证占比之和等于总量；真实会话只画 Top 6、其余合并「其它会话」；账本无 `kind` 列，**不同私聊对象暂时分不开** |
| 常量收口 | 新增 `constant/AppSettingKeys`（全部 `app_setting` 键 + `USER_EDITABLE` 白名单）与 `constant/TuningDefaults`（出厂阈值、默认关键词 / 正则、账本窗口上限）；整理上限、刷新阈值不再散落各处 | `PUT /api/settings` 只接受白名单键，网页改不了 `auth_password_hash`、补拉游标、归纳锚点；`digest_max_messages / digest_max_attempts` 可配，写坏 / 越界一律回落出厂值 |
| 密钥外置 | `application.yaml` 只留 `${DB_URL:...}` / `${DB_PASSWORD:}` / `${DIFY_WORKFLOW_API_KEY:}` 占位符；本机私密值走 `deploy/java.properties`（已 gitignore，`spring.config.import` 可选载入），生产走环境变量 | `SecretsHygieneTest` 守门，防止明文凭据、部署专用 IP、Dify App Key 再被写回；`.env.example` / `java.properties.example` 只留空值。**改值才算轮换**，见 [`../deploy/README.md`](../deploy/README.md) §10.2 |

**三条约定**：

- `/api/agent/**` 全部由 `X-Agent-Token` 鉴权（新增的 `POST /api/agent/token-usage` 也一样）；
  网页只读的 `/api/token-usage/**` 走登录态，两者不混用。
- 账本**只追加、只读展示**，没有删除 / 清空入口，也不参与任何业务判定；
  一条消息最多记一行，插件侧用 fire-and-forget 上报并吞掉异常。
- 会话分账**只认 `chat_id`，且它只记群号**：私聊与问答类调用写 NULL，统一归到「未归属会话」，
  不猜对象、不摊进群聊。展示名只 JOIN `kind='GROUP'` 的 `chat_alias` / `chat_setting`，
  逗号串取第一段；服务端只合并扇区、不下发浮点占比，百分比由前端按扇区之和算。

### 5.6 中期（按触发条件做）

| 方向 | 讨论结论 | 触发条件 |
|---|---|---|
| **PostgreSQL + pgvector** | 换库的收益不是「向量放数据库」，而是 pgvector 的 ANN 索引和 SQL 过滤。迁移还要处理中文全文索引、`ON DUPLICATE KEY`、`AUTO_INCREMENT`、`TINYINT`、`ON UPDATE CURRENT_TIMESTAMP` 等差异 | 消息量达到几十万级、暴力扫描明显变慢，或需要按元数据做向量过滤时 |
| **MinIO 对象存储** | 当前文件任务本地落盘且功能已冻结。若恢复文件/图片能力，MinIO + 预签名 URL 比本地路径更适合多端访问和统一备份 | 文件功能解冻、需要跨设备访问或统一备份时 |
| **缓存热点配置** | 画像、风格、`chat_setting`、`app_setting` 每次请求都查库；可先用 Caffeine 进程内缓存，多实例时再换 Redis | 接口延迟成为瓶颈，或出现多实例 |
| **插件侧跳过 Dify** | 群聊噪音只有 @ 或前缀触发时才会进 Dify，收益有限；若要做，最小改动是 `reply-context` 多返回一个 `noise` 标记 | 确认 @ 触发的噪音消息占比高，且接受一次小改插件 |
| **按群覆盖噪音规则** | 现在四个键是全局的；不同群噪音风格不同，可在 `chat_setting` 增加覆盖列或独立表 | 全局规则误伤某个群时 |

### 5.7 暂缓 / 不采用

| 方案 | 结论 | 原因 |
|---|---|---|
| **Redisson 延迟队列替代 60s 提醒扫描** | 暂缓 | 提醒量是每天个位数到几十条，60s 扫描简单、可观测、重启不丢；等提醒量或实例数上来再说 |
| **Redis Stream / 独立 MQ** | 暂缓，再评估 | 当前单机单消费者，丢消息的根因是 NapCat 掉线期间没有收到事件，MQ 补不回来；先用「重连补拉 + `message_id` 幂等」解决。等出现消费端重启丢消息、需要削峰或多消费者时再看 |
| **模型分级** | 不做 | 当前 Dify 只有一条 Chatflow + 一条记忆 Workflow，拆模型要复制应用、同步提示词、维护路由，收益不确定；控成本先靠噪音过滤、限流和触发判定 |
| **「把重心放到 Dify」** | 调整方向 | Dify 是 token 成本中心，不是省 token 的地方；正确方向是**薄插件 + 厚 Spring + 精准 Dify**——插件只做收发和 I/O，Spring 做状态与预过滤，Dify 只做语义 |

### 5.8 演进顺序

```text
已完成：噪音预过滤（digest / extract）
       登录/验证码/Redis Session（含 QQ 私聊验证码登录）→ API 限流 + reply-context 令牌桶
       NapCat 掉线补拉（重连回查 + message_id 幂等）
       规则页噪音过滤（取代原拦截规则）→ Token 账本 → 常量收口 → 密钥外置（§5.5）
       原拦截规则运行时下线：CRUD / 规则缓存 / 模板回复全删，两张旧表保留不 DROP
   ↓
中期（按触发条件）：缓存 → pgvector → MinIO → 插件侧噪音跳过 → 按群规则
   ↓
再评估：Redis Stream / 独立 MQ（前提是出现消费端丢消息或需要削峰）
```
