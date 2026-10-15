# QQ 消息智能助手 · 五大优化方案定稿

> 本文是 `design/prompt_init.md` 中五个「核心功能与优化方案」的详细定稿，记录讨论后确认的最终分工、数据流、状态机与 API 契约。
> 组件边界与贯穿全局的抽象见 [`architecture.md`](./architecture.md)。
> 范围：仅到「表名 + 用途 + 关键字段」与「API 契约草案」级别；字段级建表、JSON Schema、代码实现留给 Phase 1–4。

**目录**
1. [私聊拟人回复与草稿模式](#一私聊拟人回复与草稿模式)
2. [统一回复任务（防重 + 前置拦截）](#二统一回复任务防重--前置拦截)
3. [备忘录与分级提醒](#三备忘录与分级提醒)
4. [复杂任务处理（文件业务后置）](#四复杂任务处理文件业务后置)
5. [Agent 持续学习（记忆分离）](#五agent-持续学习记忆分离)

---

## 一、私聊拟人回复与草稿模式

### 目标
私聊场景下模仿用户语气人性化回复；非紧急/低置信回复不直接发送，而作为草稿交由用户确认。

### 最终分工

| 组件 | 职责 |
|---|---|
| Dify | 生成回复内容，并**全权输出发送决策** `send_action` |
| LangBot | 执行拟人延迟（仅私聊）；按 `send_action` 路由 |
| Spring Boot | 统一草稿中心（`draft` 表） |
| Vue | 草稿中心页，一键确认 / 拒绝 |

### 数据流

```
Dify → { content, send_action }
  SEND   → LangBot 拟人延迟 → 直接发送
  DRAFT  → POST /api/drafts(type=REPLY) → 推 Vue 草稿中心 → 用户确认 → LangBot 发送
  SILENT → 仅记录，不推不送
```

### 拟人延迟（LangBot 层，仅私聊）

- **公式**：`延迟 = 1.5s + 字数 × 0.15s + 随机抖动(0~1.0s)`
- **上限**：15s（超出封顶，避免用户久等）
- **抖动是灵魂**：固定延迟有机械感，必须加随机量
- **范围**：仅私聊。群聊不启用（群聊中「机器人感」反而更合适，也不该刷屏等待）

### API 契约草案

| 接口 | 调用方 | 作用 |
|---|---|---|
| `POST /api/drafts` | LangBot / Dify | 创建草稿（type 可 REPLY/FILE/MEMO/FACT）|
| `GET /api/drafts?status=PENDING` | Vue | 草稿列表 |
| `POST /api/drafts/{id}/confirm` | Vue | 确认（REPLY/FILE → 通知 LangBot 发送）|
| `POST /api/drafts/{id}/reject` | Vue | 拒绝 |

### 关键决策记录

| 决策点 | 结论 |
|---|---|
| 谁判定「低置信/非紧急」| **Dify 全权决定**，直接输出 `send_action`，LangBot 不做阈值路由 |
| 草稿超时 | **保持 PENDING**，不自动过期/丢弃 |
| 草稿存储 | 复用**统一草稿中心**（不给本方案单独建表）|
| 延迟参数 | 更拟真档：每字 0.15s，上限 15s |

---

## 二、统一回复任务（防重 + 前置拦截）

### 目标
识别群内「收集信息」类通知，自动读知识库回复；防止重复回复；高频固定回复绕过 Dify 以省 Token、提速度。

### 最终分工

| 组件 | 职责 |
|---|---|
| LangBot | 前置拦截（关键词+正则，规则热加载）；命中后**仍查状态表防重** |
| Dify | 拦截未命中时的复杂意图识别 + 生成回复 |
| Spring Boot | `collection_task` 状态表（防重）+ 拦截规则配置存储 |

### 数据流

```
群消息 → LangBot 前置拦截（关键词 + 正则，规则从配置表热加载）
  命中 → 查状态表 check {chat_id, task_key}
           ├─ 未回复 → 查画像生成回复 → 发送 → mark
           └─ 已回复 → 静默跳过
  未命中 → Dify 识别意图 → 同样走 check / mark 防重
```

### 关键决策记录

| 决策点 | 结论 |
|---|---|
| `task_key` 定义 | `chat_id + task_type + 时间窗口`（当天/本周可配）。既防重又不误拦新批次 |
| 拦截规则管理 | **可配置热更新**：规则存 Spring Boot 配置表，LangBot 启动/定时拉取，改规则不改代码不重启 |
| 拦截命中是否仍防重 | **是**。正则命中后仍查状态表确认未回过再发，否则拦截本身会成为重复回复来源 |
| 规则表达方式 | **关键词 + 正则混合**：先关键词快速匹配，再正则捕获（如「学号/姓名/专业」）|

### API 契约草案

| 接口 | 调用方 | 作用 |
|---|---|---|
| `POST /api/collection-tasks/check` | Dify / LangBot | 查是否已回复（幂等）|
| `POST /api/collection-tasks/mark` | Dify / LangBot | 标记已回复 |
| `GET /api/collection-tasks/rules` | LangBot | 拉取前置拦截规则（热更新）|

---

## 三、备忘录与分级提醒

### 目标
识别邀约、通知等关键信息，存入备忘录并按重要性分级动态提醒。

### 最终分工

| 组件 | 职责 |
|---|---|
| Dify | 识别邀约/通知 + 抽取结构化 + 打 importance(1-5) + category |
| Spring Boot | `memo` 存储 + Quartz 定时扫描 + 提醒状态机 |
| LangBot | `POST /api/langbot/send_message` 主动发送；识别 QQ 内「完成/取消」闭环 |
| Vue | 备忘列表、编辑、完成/取消、低置信度草稿确认 |

### 重要性分级 rubric（写入 Dify Prompt，必须给边界 + few-shot）

| 级别 | 判定标准 | 例子 |
|---|---|---|
| 5 | 有明确时间 + 不做后果严重 | 考试、面试、答辩、会议 |
| 4 | 有明确时间 + 重要但可调整 | 重要邀约、缴费截止 |
| 3 | 普通邀约 / 一般截止 | 班会、聚餐 |
| 2 | 低价值待办，可做可不做 | 帮带东西 |
| 1 | 纯信息，无行动项 | 通知类，仅存档 |

分类标签：`学业 / 社交 / 健康 / 财务 / 日常 / 其他`（枚举兜底，允许扩展自由标签）。

### 提醒策略表（默认，已确认）

| 级别 | 提前量 | 次数 | 语气 |
|---|---|---|---|
| 5 | 24h / 2h / 30min | 3 | 强 |
| 4 | 24h / 1h | 2 | 较强 |
| 3 | 2h | 1 | 温和 |
| 2 | 当天 | 1 | 轻 |
| 1 | 无 | 0 | 静默 |

### 数据流（含主动推送 + 闭环）

```
① 消息 → Dify 识别为邀约/通知 → 抽结构化 + 打分 → 
② 置信度路由：高置信（时间+要素齐全）→ POST /api/memos 直接入库
             低置信 → 推 Vue 草稿确认 → 确认后入库
③ Spring Boot 存储 + 按策略表计算 remind_time 列表
④ Quartz 到期扫描 → 按级别生成提醒文案 → POST /api/langbot/send_message → QQ
⑤ 用户闭环：
   - QQ 内回复「完成/取消」→ LangBot 前置拦截识别 → PATCH /api/memos/{id}
   - 或 Vue 上点完成/取消
⑥ 状态同步 Vue
```

### 状态机

```
ACTIVE(待提醒) → REMINDED(已提醒) → DONE(完成)
                 ↘ AWAITING_REPLY(提醒已发,等回复) → DONE / CANCELLED
                 ↘ OVERDUE(逾期未处理)
```

### 关键决策记录

| 决策点 | 结论 |
|---|---|
| 推送通道 | **方案 A**：Spring Boot Quartz 调度 + LangBot `send_message` 主动发送（调度/状态用 Java 强项，LangBot 只加发送接口）|
| 提醒策略 | 采用上表默认策略 |
| 备忘创建 | 按置信度草稿确认 |
| 闭环方式 | QQ + Vue 双闭环 |
| 隐性设计 | QQ 内闭环要求 LangBot 维护「当前激活提醒映射」`memo_id → 用户`，提醒发出后处于等回复状态，才能正确路由用户的「完成/取消」到对应 memo |

### API 契约草案

| 接口 | 调用方 | 作用 |
|---|---|---|
| `POST /api/memos` | Dify Tool | 创建备忘（含 importance/category）|
| `GET /api/memos?status=` | Vue | 备忘录列表 |
| `PATCH /api/memos/{id}` | Vue / LangBot | 编辑 / 完成 / 取消 |
| `POST /api/langbot/send_message` | Spring Boot → LangBot | 主动推送提醒到 QQ |

---

## 四、复杂任务处理（文件业务后置）

> ⚠️ **本方案对 `prompt_init.md` 原方案有一处重要修正**：原方案要求「文件在 Spring Boot 侧处理、Dify 调 Spring Boot 的文件处理 Tool」。经讨论，**文件 I/O 改放 LangBot 插件（Python）**，Spring Boot 不再处理文件流，只保留事实画像读取 API。修正理由见下。

### 修正理由（核心认知）

- **Dify agent 并不能「直接」改文件**：LLM 只能读/生成文本，无法读写二进制（xlsx/docx 是 zip 二进制）。「丢给 Dify」实际是「代码换个地方放」——要么 Dify Code 节点，要么外部 Tool，要么另有代码写文件。
- **改文件的代码放 Python 最省事**：LangBot 是 Python；最好的表格/文档库 `openpyxl`(xlsx)、`python-docx`(docx)、`pypdf`(pdf) 全是 Python；文件本就是 LangBot 从 QQ 下载的，就近处理最自然。
- **Java(POI) 改 Excel 是本链路最痛的路径**，且固定代码难应对灵活任务。
- 三个备选（LangBot 插件 / Dify Code 节点 / Spring Boot Java）中最优为 **LangBot 插件**：Dify Code 节点受沙箱第三方库、二进制文件访问、调试困难三大限制；Spring Boot 则如上述。

### 架构原则

**LLM 决策「填什么」（灵活）+ Python 执行「读与写」（通用）**——两件事拆开：
- 「决定填什么」是灵活的、随任务变化的 → 交给 Dify（LLM）
- 「读结构 / 按指令写回」是机械的、通用的 → 留在 Python，量小且无需模板库

### 最终分工

| 组件 | 职责 |
|---|---|
| LangBot 插件(Python) | 下载文件、结构化读写（openpyxl/python-docx/pypdf）、任务状态机、QQ 反问补信息、回传 |
| Dify | 意图识别 + 决策「填什么」，输出结构化指令（`fills` + `missing`）|
| Spring Boot | 事实画像存储 + `GET /api/profile/{sender_id}` |
| Vue | 草稿确认 + 状态展示 |

### 数据流（异步）

```
① 文件 → NapCat → LangBot 插件下载
② 插件结构化：读成 { cells: [{ref:"A1", text:"学号"}, ...] }   ← 机械通用，无业务逻辑，无模板库
③ 插件带「结构化文本 + sender_id」请求 Dify 决策
④ Dify：查画像（Tool→Spring Boot）+ 决策 → 返回 { fills:[{"A2":"张三"}], missing:["专业"] }
⑤ missing 非空 → 插件在 QQ 反问用户补充 → 补完增量重走 ④
⑥ 插件按 fills 机械写回 → 生成新文件
⑦ 置信度路由：
     高置信（字段全精确匹配画像）→ 直接回传 QQ
     低置信 → 推 Vue 草稿确认 → 确认后回传
⑧ 回传 QQ，状态同步 Vue
```

### 任务状态机（宿主在 LangBot 插件内）

```
PENDING → PROCESSING → NEED_INPUT → AWAITING_CONFIRM → SENT
                      ↘ FAILED / CANCELLED
```

- **`NEED_INPUT` 是核心分支**：填表发现画像缺字段时不卡死，流转到「补信息」，与草稿确认机制衔接。
- 个人项目用「内存字典 + 简单 JSON 落盘」即可，**不需要 Spring Boot 维护状态表**。

### 关键决策记录

| 决策点 | 结论 |
|---|---|
| 文件 I/O 位置 | **LangBot 插件(Python)**（对原方案的重要修正）|
| 同步/异步 | **异步**（提交返回 taskId，避免 Dify/LangBot 超时）|
| 信息缺失 | **QQ 里反问补充**（自然，符合拟人对话节奏）|
| 回传确认 | **按置信度**：高置信直接回传，低置信推草稿 |
| 模板库 | **不需要**。LLM 全权决定填什么，解决「固定代码难应对灵活任务」|
| 格式支持 | xlsx/docx/pdf 三库成本都低，按需加解析器，不必提前纠结 |

### 待 Phase 3 确认

- NapCat 文件收发 API（下载/上传）
- LangBot 暴露 `send_file` 接口的插件机制

---

## 五、Agent 持续学习（记忆分离）

> prompt_init 标注为「核心难点」。核心是为防止 System Prompt 漂移而做的「事实 / 风格」分离。

### 为什么必须分离（理论根基）

| | 事实记忆 | 风格记忆 |
|---|---|---|
| 内容 | 学号、姓名、专业、目标 | 语气、性格、说话方式 |
| 结构 | 结构化、可验证 | 模糊、主观 |
| 用错代价 | 高（学号填错）| 低（语气不像，不致命）|
| 更新节奏 | 实时、单条、高置信度 | 定期、批量、需大量样本 |
| 失败模式 | 污染（存错信息）| 漂移（prompt 不稳定）|

**关键洞察**：事实记忆可逐条实时写（可验证）；风格记忆**绝不能逐条实时改**——每学一条就改一次 System Prompt 会让 prompt 像布朗运动一样漂移（今天幽默明天高冷）。故「禁止实时改，改用定期总结」是防漂移的正确机制。

### 最终分工

| 组件 | 职责 |
|---|---|
| Dify | 事实抽取（含主语归属判断）+ 风格定期总结工作流 |
| Spring Boot | 事实记忆存储、聊天日志、风格画像存储、定时调度、调 Dify 配置 API |
| LangBot | 记录聊天日志（`POST /api/message-logs`）|
| **Vue（新增：记忆总控台）** | 事实信息透明展示 + 增删改 + 总结频率配置 |

### 数据流

**① 事实记忆（实时 + 三重保障）**
```
用户「我学号2023001」→ Dify 抽取 + 主语归属判断
  ├─ 高置信（明确「我本人」）→ PUT /api/profile/facts 直接写
  └─ 低置信 → 推 Vue 草稿确认 → 用户点了才入库
用户改口 → 直接覆盖旧值
用户在 Vue 记忆总控台 → 手动增/删/改（最终兜底，纠错全靠它）
会话开始 → GET /api/profile/{sender_id} 变量注入
```

**② 风格记忆（定期 + 可配置）**
```
LangBot 记聊天日志 → message_log 表
定时任务（频率/窗口用户可配）→ 调 Dify「风格总结」工作流（近 N 天日志）
→ 输出风格画像更新 → 写 user_style_profile
→ 通过 Dify 配置 API 更新用户画像 Prompt
```

### 记忆总控台（Vue，本方案最有价值的产出）

1. **事实信息页**：表格列出 agent 记住的所有事实（学号/姓名/专业/目标…），每条可增删改——对「LLM 抽错」的终极安全网，用户自查纠错。
2. **风格画像页**：展示当前注入的画像 Prompt 内容，可手动编辑（比等定期总结更直接）。
3. **设置页**：总结频率 / 窗口用户自选。

### 关键决策记录

| 决策点 | 结论 |
|---|---|
| 风格注入方式 | **更新画像 Prompt**（稳定、省 token；Spring Boot 调 Dify 配置 API）|
| 低置信事实 | **草稿确认**（与全项目置信度策略一致）|
| 事实冲突（改口）| **直接覆盖**旧值（个人项目，表结构最简）|
| 总结窗口 | **用户可配**（每日+近7天 / 每日仅当日 / 每周，三选一），通过 Vue 设置页 |
| 主语归属 | **必须判断信息归属主体**：只有明确「我本人」才高置信存；提「别人」降置信或不存（防污染，如「我室友学号是…」）|

### API 契约草案

| 接口 | 调用方 | 作用 |
|---|---|---|
| `GET /api/profile/{sender_id}` | Dify | 会话注入事实记忆 |
| `PUT /api/profile/facts` | Dify | 高置信事实写入 |
| `GET/POST/PUT/DELETE /api/profile/facts` | Vue 总控台 | 手动 CRUD |
| `POST /api/message-logs` | LangBot | 记聊天日志（异步/可批量）|
| `GET/PUT /api/settings` | Vue | 总结频率等配置 |
| `POST /api/style-profile/update` | Dify 总结工作流 | 写回风格画像 |

### 待 Phase 4 确认

- **Dify 应用配置 API**：更新 system prompt 的接口调用方式、鉴权、字段名，需查最新官方文档。

---

## 附：跨方案复用的抽象（详见 architecture.md）

| 抽象 | 被哪些方案复用 |
|---|---|
| 统一草稿中心（`draft` 表）| ①②③⑤（回复/文件/备忘/事实四类草稿）|
| 统一消息日志（`message_log` 表）| ⑤ 风格总结原料；全链路排查 |
| LangBot 主动发送接口（`send_message` 发文本）| ①③（草稿 REPLY 确认后发送 / 备忘录提醒推送）|
| LangBot `send_file`（发文件，与上并列）| ①④（草稿 FILE 确认后发送 / 文件回传 QQ）|
