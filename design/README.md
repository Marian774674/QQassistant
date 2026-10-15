# design/ —— 设计文档索引

QQ 消息智能助手的项目设计文档。**开发时的唯一准据**。

| 文档 | 内容 | 何时看 |
|---|---|---|
| [`prompt_init.md`](./prompt_init.md) | 项目原始纲领：组件、5 大功能、规则、5 阶段工作流 | 想了解项目缘起与总目标 |
| [`architecture.md`](./architecture.md) | 分层架构、**职责红线**、**三个贯穿全局的抽象** | 写任何代码前先看边界 |
| [`optimization_schemes.md`](./optimization_schemes.md) | 五大优化方案定稿（分工/数据流/状态机/决策记录） | 实现某个功能时的设计出处 |
| [`database_schema.md`](./database_schema.md) | 10 张表的字段级结构 + 可执行 DDL | 建表、写实体/Mapper 时 |
| [`api_contract.md`](./api_contract.md) | 全部对外接口契约（请求/响应/错误） | 写 Controller / 对接时 |
| [`plans/`](./plans/) | 分期实现计划（Task 级 TDD 步骤） | 开发时逐步执行 |

**注意**：`optimization_schemes.md` 在冲突时**优先于** `prompt_init.md`（见方案④：文件 I/O 已从 Spring Boot 改到 LangBot 插件）。

## 分工速记

- **Dify**：语义理解、意图识别、对话流、决策「填什么/发不发」——**不写业务逻辑、不碰文件流**。
- **Spring Boot**：状态机、持久化、定时调度、REST API——**不直连 NapCat**。
- **LangBot**：QQ 收发、正则拦截、拟人延迟、**文件 I/O**、主动发送接口。
- **Vue**：展示、人工确认、配置——不做业务判定。
