# design/ —— 设计文档索引

QQ 消息智能助手的项目设计文档。**开发时的唯一准据**。

| 文档 | 内容 | 何时看 |
|---|---|---|
| [`design_overview.md`](./design_overview.md) | **简版设计文档**：项目背景、功能列表、功能模块、工作流、后续优化 | 快速了解项目全貌 / 汇报 |
| [`requirements.md`](./requirements.md) | **需求规格说明书**：背景目标、用例、功能/非功能需求编号、验收标准 | 想知道「要做什么、做到什么算完成」|
| [`prompt_init.md`](./prompt_init.md) | 项目原始纲领：组件、5 大功能、规则、5 阶段工作流 | 想了解项目缘起与总目标 |
| [`architecture.md`](./architecture.md) | 分层架构、**职责红线**、**三个贯穿全局的抽象** | 写任何代码前先看边界 |
| [`optimization_schemes.md`](./optimization_schemes.md) | 五大优化方案定稿（分工/数据流/状态机/决策记录） | 实现某个功能时的设计出处 |
| [`database_schema.md`](./database_schema.md) | 21 张表的字段级结构 + 可执行 DDL（权威 DDL 是 `db/schema.sql`） | 建表、写实体/Mapper 时 |
| [`api_contract.md`](./api_contract.md) | 全部对外接口契约（请求/响应/错误） | 写 Controller / 对接时 |
| [`agent_contract.md`](./agent_contract.md) | **Dify ↔ LangBot 契约**：事件输入、决策 envelope、动作语义、降级规则 | 改 Dify Chatflow / 插件分发前必读 |
| [`dify_chatflow.md`](./dify_chatflow.md) | Dify Chatflow 节点图、会话变量、提示词、手工验收清单 | 搭建/调整 Dify 应用时 |
| [`../QQMessageAssistantVue/README.md`](../QQMessageAssistantVue/README.md) | 前端工程说明与页面清单 | 改 Vue 前先看 |
| [`plans/`](./plans/) | 分期实现计划（Task 级 TDD 步骤） | 开发时逐步执行 |

**注意**：冲突时的优先级是 **`requirements.md` > `optimization_schemes.md` > `prompt_init.md`**（见方案④：文件 I/O 已从 Spring Boot 改到 LangBot 插件）。

## 分工速记

- **Dify**：语义理解、意图识别、对话流、决策「填什么/发不发」——**不写业务逻辑、不碰文件流**。
- **Spring Boot**：状态机、持久化、定时调度、REST API——**不直连 NapCat**。
- **LangBot**：QQ 收发、前置噪音判定（每条消息问 Spring）、拟人延迟、**文件 I/O**、主动发送接口。
- **Vue**：展示、人工确认、配置——不做业务判定。

---

## 实现进度

| 阶段 | 内容 | 状态 | 文档 |
|---|---|---|---|
| ① 架构 + 表结构 | 分层、红线、三个抽象、21 张表 DDL | ✅ 完成 | `architecture.md` / `optimization_schemes.md` / `database_schema.md` |
| ② Spring Boot API | 19 个控制器 / 68 个对外端点 + 调度 + LangBot 客户端 | ✅ 代码完成（461/461 测试绿）；Phase 2 加固计划保留作历史记录 | `api_contract.md` + `plans/2026-10-18-phase2-hardening.md` |
| ③ LangBot 插件 | 收发 / 前置噪音判定 / 拟人延迟 / 文件 I/O / 主动发送 / 掉线补拉（边沿 + 周期兜底）/ 用量上报 | ✅ 3-1/3-2/3-3 已落地；当前版本 **0.2.33**（`dist/Marian-qq_assistant_plugin-0.2.33.lbpkg`）；待真机联调 | `plans/2026-10-18-phase3-*.md` |
| ④ Dify 工作流 | 意图识别 / 对话状态 / 文件决策 / 风格总结 | 🟡 Chatflow + 文件 Workflow 已搭好；待真机联调 | `agent_contract.md` / `dify_chatflow.md` |
| ⑤ Vue 前端 | 草稿中心 / 备忘 / 记忆总控台 / 设置 / Token 用量（含会话占比饼图） | ✅ 14 页已对接真实接口（`npm run build` 通过）；待真机联调 | [`../QQMessageAssistantVue/README.md`](../QQMessageAssistantVue/README.md) |

### Phase 2 计划索引

| 计划 | 覆盖 | 状态 |
|---|---|---|
| [`plans/2026-10-15-phase2-1-foundation-draft-log.md`](./plans/2026-10-15-phase2-1-foundation-draft-log.md) | 数据源接入 + 统一草稿中心 + 统一消息日志 | 参考（实际实现已扩展，见下方说明） |
| [`plans/2026-10-17-phase2-2-profile-setting-filetask.md`](./plans/2026-10-17-phase2-2-profile-setting-filetask.md) | 修复既有缺陷 + 画像（事实/风格）+ 设置 + 文件任务镜像 | 待执行 |
| [`plans/2026-10-17-phase2-3-langbot-client-and-scheduler.md`](./plans/2026-10-17-phase2-3-langbot-client-and-scheduler.md) | LangBot 客户端 + 草稿分发落地 + 定时提醒推送 | ✅ 已执行 |
| [`plans/2026-10-18-phase2-hardening.md`](./plans/2026-10-18-phase2-hardening.md) | **代码审计后的 5 项加固**（memo importance 校验、事实 confidence、调度配置/事务、测试配置归位） | 待执行 |

### Phase 3 计划索引

| 计划 | 覆盖 | 依赖 |
|---|---|---|
| [`plans/2026-10-18-phase3-1-plugin-core.md`](./plans/2026-10-18-phase3-1-plugin-core.md) | 插件骨架 + 事件监听 + ~~前置拦截~~（现为前置噪音判定）+ Dify 分发 + 拟人延迟 + 备忘闭环 + 日志 | Phase 2 加固完成 |
| [`plans/2026-10-18-phase3-2-file-tasks.md`](./plans/2026-10-18-phase3-2-file-tasks.md) | 文件下载 / xlsx·docx 读写 / pypdf 只读 / NEED_INPUT 反问 / 文件回传 | 3-1 |
| [`plans/2026-10-18-phase3-3-send-bridge.md`](./plans/2026-10-18-phase3-3-send-bridge.md) | 主动发送桥（Spring Boot → LangBot / NapCat）+ 延迟 + OUT 日志 + 幂等 | ✅ 已实现（`QQMessageAssistantPython/send_bridge`，待真机冒烟） |

### 后续优化（[`design_overview.md`](./design_overview.md) §5）进度

| 项 | 状态 | 落点 |
|---|---|---|
| §5.1 QQ 私聊验证码登录（密码 + 图形码兜底） | ✅ 完成 | `api_contract.md` §16、`auth/` |
| §5.2 自动回复总开关「彻底一个字都不说」 | ✅ 完成 | `core/reply.py` 静默上下文、`reply_gate.py` |
| §5.3 限流（API + reply-context 令牌桶） | ✅ 完成 | `design_overview.md` §5.3 |
| §5.4 NapCat 掉线补拉 | ✅ 完成 | `core/backfill.py` / `core/napcat.py`、`api_contract.md` §15 |
| §5.5 规则页噪音过滤（取代原拦截规则）/ Token 账本 / 常量收口 / 密钥外置 | ✅ 完成 | `design_overview.md` §5.5、`api_contract.md` §17、`core_features.md` §14 |
| §5.6 中期项 / §5.7 暂缓项 | ⏸ 按触发条件 | `design_overview.md` §5.6–§5.7 |

> **2026-11 功能变更**：原「拦截规则」运行时彻底下线（CRUD 接口、插件规则缓存、模板回复全删），
> 噪音过滤顶到它原来的前置位置；前端 `/rules` 改为噪音过滤页，设置页不再放该分区。
> 两张旧表保留不 DROP。见 `api_contract.md` §七 / §17.7。
>
> 全量回归：Java 461/461（61 类）、插件 215/215、发送桥 31/31、`npm run build` 通过。
> 插件当前版本 **0.2.33**（本轮风格画像修正改了插件侧取画像的来源：由消息发送者改为主人，
> 因此必须重新打包上传；Spring 与 Dify 也各有改动，部署顺序见
> [`../deploy/README.md`](../deploy/README.md) §6）。细节见 [`../README.md`](../README.md) 的「测试」一节与
> [`../deploy/README.md`](../deploy/README.md) §10.3（共享测试库 FULLTEXT 维护）。

> 仓库根的 `.github/pull_request_template.md` 已配置，PR 会自动带出自查清单。

> **关于 Plan 2-1**：该计划写于实际编码之前，其中提到的类名（`ApiResponse` / `BizException` / `CreateDraftRequest` / `MessageLogController`）与**实际落地**的实现（`Result` / `BusinessException` / `DraftCreateRequest` / `MessageController`）不一致，MyBatis-Plus 版本也从 3.5.17 落到 3.5.9。**以实际代码为准**，Plan 2-2 / 2-3 已按实际命名编写。Plan 2-1 保留作为当时的思路记录。

> **关于 Phase 3 的三个决策**（详见 3-1 第〇节）：
> 1. 插件**绕过** LangBot 内置 Dify 管线，自己编排；群聊触发规则由插件实现。
> 2. Dify 的 `answer` 必须是结构化 JSON（`action` + payload），插件只做机械分发；解析失败**不再原文发送**，降级为 SILENT + 日志（见 `agent_contract.md`）。
> 3. 备忘「完成/取消」由 Chatflow 输出 `MEMO_CLOSE` 决策，插件查 Spring Boot 的 `AWAITING_REPLY`/`REMINDED` 列表并 PATCH，不再在插件内存里维护映射（原 `closed_loop` 快捷路径已从事件监听器移除）。
>
> 另：主动发送由独立 Python 桥（`:5301`）实现，Spring Boot 的 `langbot.base-url` 已指向它；LangBot 官方发送 API 的真实路径与请求体见 3-3。
> 启动桥：`cd QQMessageAssistantPython && .venv/Scripts/python.exe -m send_bridge`（环境变量见 `send_bridge/.env.example`）。
> 发送安全：同一 `bizRef` 并发只发一次（在途 409）、文件通道仅在 LangBot 4xx 时回落 NapCat、提醒按「备忘 id + 提醒 id」各自幂等且不做拟人延迟。

>
> **Phase 3 迁移**：已有库执行 `QQMessageAssistantJava/src/main/resources/db/migrations/2026-10-27-phase3-file-task-and-log.sql`；新库直接用 `db/schema.sql`。
>
> **部署**：LangBot + NapCat + 发送桥的 Docker 编排与操作手册见 [`../deploy/README.md`](../deploy/README.md)。
