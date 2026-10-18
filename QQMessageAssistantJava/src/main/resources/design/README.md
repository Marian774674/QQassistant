# QQ 消息智能助手 · 设计文档索引与进度总览

> 本目录是项目的**单一设计入口**。想快速了解「现在做到哪、下一步做什么、某设计在哪份文档」，从这里开始。

---

## 一、各阶段进度

| 阶段 | 目标 | 状态 | 设计/计划文档 |
|---|---|---|---|
| **Phase 1** | 整体架构 + 数据库表结构设计 | ✅ 完成 | [`architecture.md`](./architecture.md)、[`database_schema.md`](./database_schema.md) |
| **Phase 2** | Spring Boot 后端实现（27 端点 + 调度 + 出向客户端） | ✅ 完成 | 见下方「Phase 2 实施计划」三份 |
| **Phase 3** | LangBot 插件侧实现（收发消息、`send_message`/`send_file` 服务端、桥接） | ⬜ 待启动 | [`api_contract.md`](./api_contract.md) 第二节 B 组 |
| **Phase 4** | Dify 工作流集成（画像 Prompt 注入、Tool 定义、提示词约束） | ⬜ 待启动 | [`api_contract.md`](./api_contract.md) 第十三节 |

### Phase 2 实施计划（已逐 Task 落地并全量回归通过）

1. [`plan/2026-10-15-phase2-1-foundation-draft-log.md`](./plan/2026-10-15-phase2-1-foundation-draft-log.md) — 地基：草稿中心 + 消息日志 + 备忘 + 防重 + 拦截规则
2. [`plan/2026-10-17-phase2-2-profile-setting-filetask.md`](./plan/2026-10-17-phase2-2-profile-setting-filetask.md) — 画像（事实/风格）+ 全局设置 + 文件任务镜像
3. [`plan/2026-10-17-phase2-3-langbot-client-and-scheduler.md`](./plan/2026-10-17-phase2-3-langbot-client-and-scheduler.md) — LangBot 出向客户端 + 草稿分发落地 + 到期提醒调度
4. [`plan/2026-10-18-phase2-hardening.md`](./plan/2026-10-18-phase2-hardening.md) — 收口加固（H1–H7 缺陷修复）

---

## 二、设计文档导航

| 文档 | 内容 | 何时查 |
|---|---|---|
| [`architecture.md`](./architecture.md) | 三贯穿抽象、组件职责、分层与红线 | 理解整体设计、判断改动归属 |
| [`database_schema.md`](./database_schema.md) | 10 张表 DDL、索引、通用约定 | 加字段/写实体/查状态枚举取值 |
| [`api_contract.md`](./api_contract.md) | Spring Boot + LangBot 对外接口契约 | 对接前后端、写测试断言 |
| [`optimization_schemes.md`](./optimization_schemes.md) | 五个优化方案的设计出处 | 追溯某功能为什么这么设计 |

---

## 三、架构红线（速记）

- **Spring Boot 不直连 NapCat**：所有发 QQ 动作（文本/文件）一律经 `LangBotClient` → LangBot。
- **状态机归应用层**：DB 枚举字段用 `VARCHAR` 存语义值，合法性/流转由 Service 白名单校验，不建 ENUM/CHECK/外键约束。
- **文件任务状态机真身在 LangBot 插件**：`file_task` 表仅供 Vue 只读展示，Spring Boot 只镜像、不参与业务判断。
- **调度只作触发器**：`@Scheduled` 只负责定时，业务全在可独立测试的方法（如 `scanOnce()`）里。

---

## 四、验收基线（Phase 2）

- **全量测试**：`mvn clean test` → **32 tests, 0 failures**（测试库 `qq_assistant_test`，`schema.sql` 幂等自动建表，`@Transactional` 用例级回滚）。
- **接口覆盖**：Spring Boot 端 27 个端点全部实现，可在 Swagger（`/swagger-ui/index.html`）核对；剩 2 个出向端点（`send_message`/`send_file`）由 LangBot 侧在 Phase 3 实现服务端。

---

## 五、进入 Phase 3 的衔接点

1. **LangBot 服务端**：按 [`api_contract.md`](./api_contract.md) 第二节 B 组 + 第十二节实现 `POST /api/langbot/send_message`、`POST /api/langbot/send_file`（Spring Boot 侧客户端已就绪，含 `bizRef` 供 QQ 内闭环路由）。
2. **读超时调整**：`send_message` 桥内含拟人延迟，联调时把 `langbot.read-timeout-ms` 调大（建议 20s），避免「实际已发出、响应超时」误判回滚。
3. **文件状态回写**：LangBot 侧文件任务状态变更时调 `POST /api/file-tasks/sync` 镜像（按 `taskRef` 幂等）。
4. **QQ 闭环路由**：LangBot 需维护「当前激活提醒 `memo_id` → 用户」映射，才能把用户回的「完成/取消」路由到正确 memo 并调 `PATCH /api/memos/{id}`。
