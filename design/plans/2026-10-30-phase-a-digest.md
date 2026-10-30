# Phase A：数据底座 + 消息整理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让「打开前端就知道你不在时群里发生了什么」可用——消息落库带人名群名、每小时的整理任务、`/home` 首页卡片与重要项弹窗。

**Architecture:** 数据源是已有的 `message_log`（插件在 @ 判断之前就写，见 `components/event_listener/default.py:172`）。每小时任务按群切段交给 Dify Workflow 提炼要点，Spring 校验证据 id 后落 `chat_digest_item`；前端打开 `/home` 读「上次看到现在」的整理结果。所有 LLM 产出必须带 `source_msg_ids`，证据对不上的项直接丢弃。

**Tech Stack:** Java 21 / Spring Boot 3.4.12 / MyBatis-Plus / RestClient；Python 3.12 LangBot 插件；Vue3 + TypeScript + Vite；Dify Workflow（blocking 模式）。

**Spec:** `design/core_features.md`（§5 全局设计、§6 消息整理、§9 表结构）。设计定稿于 2026-10-30。

## Global Constraints

- 结果包装统一 `Result{code,message,data}`，`code == 0` 表示成功；业务拒绝抛 `BusinessException`（由 `GlobalExceptionHandler` 转 200 + `code!=0`）。
- 新增表一律 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`，主键 `BIGINT UNSIGNED AUTO_INCREMENT`，`created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP`。
- 迁移脚本只增不删：已有库执行 `src/main/resources/db/migrations/` 下的新文件，新库走 `db/schema.sql`（两边必须同时改）。
- **不自动提交 git。** 每个任务的最后一步是「停下汇报」，由用户决定何时提交。
- 不把 Dify API Key、`AGENT_OPS_TOKEN` 或数据库口令写进仓库；配置走 `application.yaml` 的占位符 + 环境变量。
- 定时任务默认关闭，由 `app_setting.digest_enabled = "1"` 打开。
- 前端零 UI 库；沿用现有 CSS 变量（`--ink` / `--ink-2` / `--hairline` / `--r-md` / `--card` 等）与 `<script setup>` + TS；`cd QQMessageAssistantVue && npm run build`（`vue-tsc -b && vite build`）必须零错误。
- 插件测试必须在插件目录下跑：`cd QQMessageAssistantPython/qq_assistant_plugin && ../.venv/Scripts/python.exe -m pytest tests -q`。

## Review Focus

这些是设计隐含、但没有哪个任务的单测会自然覆盖到的输入，各自已挂到负责该代码的任务上：

1. **一小时内没有任何新消息** → 调度不应调用 Dify、不应写空行（Task 6）。
2. **单群单小时超过 200 条** → 只取最近 200 条并记日志，不能因为量大就 OOM 或把整段塞给模型（Task 5）。
3. **模型返回的 `source_msg_ids` 里有不存在的 id** → 整项丢弃，不许落库（Task 5）。
4. **同一小时重跑（服务重启）** → `uk_chat_period` 保证覆盖而非追加（Task 6）。
5. **未勾选整理的群 / 私聊** → 完全不出现在 `/api/digest` 结果里（Task 7）。

---

### Task 1: 数据库迁移与建表

**Files:**
- Create: `QQMessageAssistantJava/src/main/resources/db/migrations/2026-10-30-phase7-digest-foundation.sql`
- Modify: `QQMessageAssistantJava/src/main/resources/db/schema.sql`（`message_log` 建表段 + 追加两张新表）

**Interfaces:**
- Produces: 表 `chat_setting`、`chat_digest_item`；`message_log` 新增列 `sender_name`、`chat_name`、`reply_kind`。
- 说明：`reply_kind` 本期只建列不使用，供后续「自动回复」计划使用（`TASK`/`CHAT`）。

- [ ] **Step 1: 写迁移脚本**

```sql
-- Phase 7：消息整理的数据底座
-- 已有库执行本文件；新库直接用 db/schema.sql。

ALTER TABLE `message_log`
  ADD COLUMN `sender_name` VARCHAR(64) NULL COMMENT '发送者昵称' AFTER `sender_id`,
  ADD COLUMN `chat_name` VARCHAR(128) NULL COMMENT '群名；私聊为 NULL' AFTER `chat_id`,
  ADD COLUMN `reply_kind` VARCHAR(8) NULL COMMENT 'OUT 专用：TASK|CHAT' AFTER `direction`;

CREATE TABLE IF NOT EXISTS `chat_setting` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `chat_id`            BIGINT UNSIGNED NOT NULL COMMENT '群号；私聊存对方 sender_id',
  `kind`               VARCHAR(8)  NOT NULL COMMENT 'GROUP|PRIVATE',
  `chat_name`          VARCHAR(128) NULL,
  `auto_reply_enabled` TINYINT(1)  NOT NULL DEFAULT 0,
  `digest_enabled`     TINYINT(1)  NOT NULL DEFAULT 0,
  `created_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat` (`chat_id`, `kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话开关';

CREATE TABLE IF NOT EXISTS `chat_digest_item` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `chat_id`        BIGINT UNSIGNED NOT NULL,
  `chat_name`      VARCHAR(128) NULL,
  `period_start`   DATETIME NOT NULL,
  `period_end`     DATETIME NOT NULL,
  `summary`        VARCHAR(512) NOT NULL,
  `importance`     TINYINT      NOT NULL COMMENT '1-5',
  `keywords`       VARCHAR(255) NULL,
  `mentions_me`    TINYINT(1)   NOT NULL DEFAULT 0,
  `source_msg_ids` VARCHAR(512) NOT NULL COMMENT 'message_log.id，逗号分隔',
  `created_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_period` (`chat_id`, `period_start`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息整理';
```

- [ ] **Step 2: 同步 `db/schema.sql`**

在 `message_log` 建表段按同样位置补三列（`sender_name`/`chat_name`/`reply_kind`），并在文件末尾追加上面两条 `CREATE TABLE`（保留 `IF NOT EXISTS`）。

- [ ] **Step 3: 在开发库上执行并验证**

Run:
```bash
cd QQMessageAssistantJava
mysql -h 192.168.100.128 -P 3307 -u root -proot qq_assistant \
  < src/main/resources/db/migrations/2026-10-30-phase7-digest-foundation.sql
mysql -h 192.168.100.128 -P 3307 -u root -proot qq_assistant -e "
  SHOW COLUMNS FROM message_log LIKE '%name%';
  SHOW TABLES LIKE 'chat_%';"
```
Expected: 列里出现 `sender_name`、`chat_name`；表里出现 `chat_setting`、`chat_digest_item`。

> 只做新增列与新建表，可安全重复执行前先确认列不存在；若 `ADD COLUMN` 报 `Duplicate column name`，说明已执行过，跳过该段即可。

- [ ] **Step 4: 停下汇报**

---

### Task 2: `message_log` 透传昵称与群名

**Files:**
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/entity/MessageLog.java`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/dto/MessageLogItem.java`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/service/MessageLogFieldsTest.java`

**Interfaces:**
- Produces: `MessageLog` / `MessageLogItem` 各新增 `String senderName`、`String chatName`、`String replyKind`。
- Consumes: 现有 `MessageLogServiceImpl.batchSave(List<MessageLogItem>)`（`BeanUtils.copyProperties` 自动搬运，无需改动实现）。

- [ ] **Step 1: 写失败的测试**

```java
class MessageLogFieldsTest {
    @Test
    void batchSave_copiesSenderNameAndChatName() {
        MessageLogItem item = new MessageLogItem();
        item.setSenderId(1001L);
        item.setChatId(2002L);
        item.setSenderName("小明");
        item.setChatName("三班群");
        item.setDirection("IN");
        item.setMsgType("text");
        item.setContent("hello");

        assertThat(item.getSenderName()).isEqualTo("小明");
        assertThat(item.getChatName()).isEqualTo("三班群");
    }
}
```

（真正要钉的是「字段能被拷贝」——用一次性 `MessageLog` + `BeanUtils.copyProperties` 断言 `getSenderName()` 非空即可，避免依赖数据库。）

- [ ] **Step 2: 运行，确认失败**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=MessageLogFieldsTest`
Expected: 编译失败，`找不到符号: 方法 getSenderName()`

- [ ] **Step 3: 加字段**

`MessageLog` 与 `MessageLogItem` 各加三个字段（带 `@Schema(description = ...)`，`MessageLog` 上加中文注释）：

```java
/** 发送者昵称；拿不到时为 null */
private String senderName;
/** 群名；私聊为 null */
private String chatName;
/** 仅 OUT：TASK|CHAT */
private String replyKind;
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=MessageLogFieldsTest`
Expected: PASS

- [ ] **Step 5: 停下汇报**

---

### Task 3: 会话开关 `chat_setting`

**Files:**
- Create: `entity/ChatSetting.java`、`mapper/ChatSettingMapper.java`、`service/ChatSettingService.java`、`service/impl/ChatSettingServiceImpl.java`、`controller/ChatSettingController.java`、`dto/ChatSettingRequest.java`
- Test: `src/test/java/.../service/ChatSettingServiceImplTest.java`

路径前缀统一为 `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/`（测试为 `src/test/java/...`）。

**Interfaces:**
- Produces:
  - `List<ChatSetting> listAll()`
  - `ChatSetting upsert(Long chatId, String kind, String chatName, boolean digestEnabled, boolean autoReplyEnabled)`
  - `List<Long> listEnabledGroupIds()` —— `kind='GROUP' AND digest_enabled=1` 的 `chat_id`
  - `ChatSettingRequest{ Long chatId; String kind; String chatName; Boolean digestEnabled; Boolean autoReplyEnabled; }`
  - `GET /api/chat-settings` → `Result<List<ChatSetting>>`
  - `PUT /api/chat-settings` → `Result<ChatSetting>`

- [ ] **Step 1: 写失败的测试**

```java
@ExtendWith(MockitoExtension.class)
class ChatSettingServiceImplTest {
    @Mock ChatSettingMapper mapper;
    @InjectMocks ChatSettingServiceImpl service;

    @Test
    void upsert_rejectsIllegalKind() {
        assertThatThrownBy(() -> service.upsert(1L, "CHANNEL", null, true, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("kind");
    }

    @Test
    void upsert_rejectsGroupWithoutChatId() {
        assertThatThrownBy(() -> service.upsert(null, "GROUP", "三班群", true, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("chatId");
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=ChatSettingServiceImplTest`
Expected: 编译失败（`ChatSettingServiceImpl` 不存在）

- [ ] **Step 3: 实现**

- `ChatSetting` 实体 `@TableName("chat_setting")`，字段与 Task 1 的 DDL 一一对应；`Boolean` 映射 `TINYINT(1)`。
- `ChatSettingMapper extends BaseMapper<ChatSetting>`，加 `@Mapper`。
- `upsert` 逻辑：`kind` 只接受 `GROUP|PRIVATE`（否则 `BusinessException("kind 只能是 GROUP 或 PRIVATE")`）；`chatId` 为 null 抛 `BusinessException("chatId 不能为空")`；按 `uk_chat` 先查后插/更新，返回落库后的实体。
- `listEnabledGroupIds()` 用 `lambdaQuery().eq(kind,"GROUP").eq(digestEnabled,true).list()` 取 `chatId`。
- Controller 沿用 `AppSettingController` 的写法（`@Tag` / `@Operation` / `@Autowired`）。

- [ ] **Step 4: 运行，确认通过**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=ChatSettingServiceImplTest`
Expected: PASS（2 个测试）

- [ ] **Step 5: 停下汇报**

---

### Task 4: `DifyWorkflowClient`

**Files:**
- Create: `config/DifyProperties.java`、`config/DifyConfig.java`、`client/DifyWorkflowClient.java`
- Modify: `client/LangBotClient.java`（构造器加 `@Qualifier("langBotRestClient")`）、`config/LangBotConfig.java`（Bean 方法加 `@Qualifier` 名）
- Modify: `src/main/resources/application.yaml`
- Test: `src/test/java/.../client/DifyWorkflowClientTest.java`

**Interfaces:**
- Produces: `Map<String,Object> run(String task, Map<String,Object> extraInputs)`
  —— 抛 `BusinessException` 表示调用失败；返回的是 Dify 的 `data.outputs`。
- 配置：`dify.workflow.base-url`、`dify.workflow.api-key`、`dify.workflow.timeout-ms`。

- [ ] **Step 1: 写失败的测试**

用 `MockRestServiceServer` 绑定一个独立构造的 `RestClient`，断言：请求体含 `response_mode=blocking`、`input.task`；正常响应返回 `outputs`；`status=failed` 或 HTTP 5xx 抛 `BusinessException`。

```java
@Test
void run_postsBlockingWorkflowAndReturnsOutputs() {
    // given: 服务端返回 {"data":{"status":"succeeded","outputs":{"items":"[]"}}}
    Map<String,Object> out = client.run("digest", Map.of("chat_id", "1"));
    assertThat(out).containsKey("items");
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=DifyWorkflowClientTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现**

- `DifyProperties`：`@ConfigurationProperties(prefix="dify.workflow")`，字段 `baseUrl`（默认 `http://127.0.0.1/v1`）、`apiKey`（默认空串）、`timeoutMs`（默认 60000）。
- `DifyConfig`：`@Bean("difyRestClient") RestClient`，`SimpleClientHttpRequestFactory` 设连接/读超时，`defaultHeader("Authorization","Bearer "+apiKey)`。
- **同时**把 `LangBotClient` 的构造参数改成 `@Qualifier("langBotRestClient") RestClient`，并在 `LangBotConfig` 的 Bean 方法上加 `@Qualifier("langBotRestClient")`——否则容器里有两个 `RestClient`，原有注入会因歧义启动失败。
- `DifyWorkflowClient.run`：`POST /workflows/run`，body `{"inputs": {...extra, "task": task}, "response_mode":"blocking", "user":"spring"}`；读 `data.status`，非 `succeeded` 抛 `BusinessException("Dify 工作流失败：" + data.error)`；返回 `data.outputs`。
- `application.yaml` 追加：

```yaml
dify:
  workflow:
    base-url: ${DIFY_WORKFLOW_BASE_URL:http://127.0.0.1/v1}
    api-key: ${DIFY_WORKFLOW_API_KEY:}
    timeout-ms: 60000
```

- [ ] **Step 4: 运行，确认通过；并跑一次全量**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=DifyWorkflowClientTest`
Expected: PASS
Run: `cd QQMessageAssistantJava && mvn test`
Expected: 全绿（确认 `LangBotClient` 的改动没破坏既有测试）

- [ ] **Step 5: 停下汇报**

---

### Task 5: `ChatDigestService` —— 整理一条时间窗并落库

**Files:**
- Create: `entity/ChatDigestItem.java`、`mapper/ChatDigestItemMapper.java`、`service/ChatDigestService.java`、`service/impl/ChatDigestServiceImpl.java`、`vo/DigestItemVO.java`
- Test: `src/test/java/.../service/ChatDigestServiceImplTest.java`

**Interfaces:**
- Consumes: `DifyWorkflowClient.run(task, inputs)`（Task 4）、`MessageLogService`（已有）。
- Produces:
  - `int digestChat(Long chatId, String chatName, LocalDateTime start, LocalDateTime end)`
    返回写入条数；窗口内无消息时返回 0 且**不调用 Dify**。
  - Dify `task=digest` 的输入键：`chat_name`、`messages`（形如 `id|昵称|时间|内容` 的行，`\n` 分隔）、`profile`、`focus`。
  - Dify 输出键：`items` —— JSON 字符串，数组元素 `{summary, importance, keywords, mentions_me, source_msg_ids:[int]}`。

- [ ] **Step 1: 写失败的测试**

```java
@Test
void digestChat_dropsItemsWhoseEvidenceIdsAreUnknown() {
    // 窗口内只有 id=1,2 两条消息；Dify 返回两项，第二项 source_msg_ids=[2,999]
    int saved = service.digestChat(9L, "三班群", start, end);
    assertThat(saved).isEqualTo(1);            // 999 不存在 → 整项丢弃
}

@Test
void digestChat_skipsDifyWhenWindowIsEmpty() {
    assertThat(service.digestChat(9L, "三班群", start, end)).isZero();
    verifyNoInteractions(dify);
}

@Test
void digestChat_truncatesToTwoHundredNewestMessages() {
    // 造 260 行；断言送进 dify 的 messages 只有 200 行，且是最近的 200 条
}

@Test
void digestChat_clampsImportanceToOneThroughFive() {
    // Dify 返回 importance=9 → 落库为 5
}

@Test
void digestChat_marksMessagesThatMentionTheOwner() {
    // owner_aliases = "小明,明哥"；窗口里有一条内容含「明哥」
    // 断言送进 dify 的 messages 里对应行以 "[@我]" 开头，另一条没有
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=ChatDigestServiceImplTest`
Expected: 编译失败

- [ ] **Step 3: 实现**

- 取消息：`message_log` 中 `chat_id=? AND direction='IN' AND created_at >= start AND created_at < end`，按 `id` 倒序取 200 条再反转为正序（保持对话顺序）。被截断时 `log.warn`。
- **规则层（确定性、先算再送）**：对每条消息算一个 `@我` 标记——`content` 里出现 `app_setting.owner_aliases` 的任一条（逗号分隔），或出现机器人 QQ 号，也算命中。命中的行在送模型时前缀 `[@我]`，提示词要求「带 `[@我]` 的行必须产出一项，importance 不低于 4」。**这一层不筛掉任何消息，只做标记**——过滤交给模型，但「有人在叫你」这件事不许交给模型去猜。
- 组输入：`messages` 每行 `{id}|{@我 可有}|{senderName 或 senderId}|{HH:mm}|{content 前 200 字}`。
- `profile` 取 `app_setting.owner_aliases`（无则空串）；`focus` 取 `app_setting.digest_focus_prompt`（无则空串）。两者都用 `AppSettingService.getOrDefault`。
- 注意：设计里「来自 `contact.importance >= 2` 的人优先」依赖 `contact` 表，那张表在 **Phase B（历史查询）** 才建。本期先不实现这一条，Phase B 落地后回头补一行查询即可——这是刻意推迟，不是遗漏。
- 解析 `items`：`ObjectMapper.readValue(..., new TypeReference<List<Map<String,Object>>>(){})`；JSON 非法 → 记日志、返回 0。
- 校验：`source_msg_ids` 必须 **非空** 且每个 id 都在本窗口实际取到的消息 id 集合里（**不是**在 200 条截断后的集合里，而是在「窗口内全部消息 id」里——避免截断误杀）；不满足则整项跳过并 `log.warn`。
- `importance` 夹到 1–5；`mentions_me` 转布尔。
- 落库：**事务内先按 `(chat_id, period_start)` 删除旧行，再批量插入**。理由：`saveOrUpdate` 是按主键判存，命中不了 `uk_chat_period`；而 `ON DUPLICATE KEY UPDATE` 在「本次比上次少了几项」时会残留旧行。删除+插入天然表达「这一小时的结果就是这些」。整个方法 `@Transactional`。

- [ ] **Step 4: 运行，确认通过**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=ChatDigestServiceImplTest`
Expected: PASS（4 个测试）

- [ ] **Step 5: 停下汇报**

---

### Task 6: 每小时整理调度

**Files:**
- Create: `schedule/DigestScheduler.java`
- Test: `src/test/java/.../schedule/DigestSchedulerTest.java`

**Interfaces:**
- Consumes: `ChatSettingService.listEnabledGroupIds()`、`ChatDigestService.digestChat(...)`、`AppSettingService.getOrDefault("digest_enabled","0")`。
- Produces: `int runOnce(LocalDateTime periodStart, LocalDateTime periodEnd)` 供测试直接调用。

- [ ] **Step 1: 写失败的测试**

```java
@Test
void runOnce_isNoOpWhenDisabled() {
    when(settings.getOrDefault("digest_enabled", "0")).thenReturn("0");
    assertThat(scheduler.runOnce(start, end)).isZero();
    verifyNoInteractions(digestService);
}

@Test
void runOnce_onlyDigestsEnabledGroups() {
    // listEnabledGroupIds 返回 [9]；断言 digestChat 只被调用一次，chatId=9
}

@Test
void runOnce_continuesAfterOneChatFails() {
    // 第一个群抛异常 → 第二个群仍被处理；runOnce 返回 1 且不抛
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=DigestSchedulerTest`
Expected: 编译失败

- [ ] **Step 3: 实现**

- `@Scheduled(cron = "0 5 * * * *")`（每小时第 5 分钟），沿用 `MemoReminderScheduler` 的「try/catch 包住整个扫描」写法。
- 窗口 = 上一个整点前 1 小时到上一个整点（`now.withMinute(0).withSecond(0).withNano(0).minusHours(1)` / `.withMinute(0)...`）。
- 逐群 `try/catch`，单群失败只记 `log.warn` 并继续。
- 开关读 `app_setting`，默认 `"0"`（关）。
- 落库幂等由 Task 5 的唯一键负责，本任务不额外处理重跑。

- [ ] **Step 4: 运行，确认通过**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=DigestSchedulerTest`
Expected: PASS（3 个测试）

- [ ] **Step 5: 停下汇报**

---

### Task 7: 整理结果的读写接口

**Files:**
- Create: `controller/DigestController.java`、`vo/DigestItemVO.java`（若 Task 5 已建则复用）
- Modify: `service/ChatDigestService.java`（加查询方法）、`service/impl/ChatDigestServiceImpl.java`
- Test: `src/test/java/.../service/ChatDigestQueryTest.java`

**Interfaces:**
- Produces:
  - `GET /api/digest?since=2026-10-30T08:00:00` → `Result<List<DigestItemVO>>`，按 `importance DESC, created_at ASC` 排序；`since` 缺省 = 24 小时前。
  - `POST /api/digest/seen` → `Result<Integer>`，把当前时间写入 `app_setting.last_digest_seen`。
  - `GET /api/digest/last-seen` → `Result<String>`，无值返回空串（前端据此决定不弹）。
  - `DigestItemVO{ Long id; Long chatId; String chatName; String summary; Integer importance; Boolean mentionsMe; String sourceMsgIds; LocalDateTime createdAt; }`

- [ ] **Step 1: 写失败的测试**

```java
@Test
void listSince_excludesPrivateAndUnwatchedChats() {
    // chat_setting 里只有 9 号群 digest_enabled=1，10 号群关着；chat_digest_item 里有 9、10、11
    // 断言只返回 9 的项
}

@Test
void listSince_sortsByImportanceThenTime() { ... }
```

- [ ] **Step 2: 运行，确认失败**

- [ ] **Step 3: 实现**

查询时先 `listEnabledGroupIds()`，再 `IN` 过滤——即使 `chat_digest_item` 里残留了未勾选群的历史行也不会漏出来（这是 Review Focus 第 5 条）。`since` 用 `FlexibleLocalDateTimeDeserializer` 支持的同一批格式解析（抽成静态方法复用，别复制两套）。

- [ ] **Step 4: 运行，确认通过**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=ChatDigestQueryTest`
Expected: PASS

- [ ] **Step 5: 停下汇报**

---

### Task 8: 插件把昵称与群名写进日志

**Files:**
- Modify: `QQMessageAssistantPython/qq_assistant_plugin/core/text.py`（新增 `peer_names(event)`）
- Modify: `QQMessageAssistantPython/qq_assistant_plugin/components/event_listener/default.py:172-175`
- Test: `QQMessageAssistantPython/qq_assistant_plugin/tests/test_text.py`

**Interfaces:**
- Produces: `peer_names(event) -> tuple[str, str]` 返回 `(sender_name, chat_name)`；任何取不到的情况返回 `("", "")`，**绝不抛异常**。

- [ ] **Step 1: 写失败的测试**

```python
def test_peer_names_reads_sender_and_group():
    event = SimpleNamespace(sender=SimpleNamespace(name="小明"),
                            group=SimpleNamespace(name="三班群"))
    assert peer_names(event) == ("小明", "三班群")

def test_peer_names_falls_back_to_empty_strings():
    assert peer_names(SimpleNamespace()) == ("", "")
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd QQMessageAssistantPython/qq_assistant_plugin && ../.venv/Scripts/python.exe -m pytest tests/test_text.py -q`
Expected: `ImportError: cannot import name 'peer_names'`

- [ ] **Step 3: 实现**

`peer_names` 依次尝试 `event.sender.name`、`event.sender_name`、`event.sender.nickname`，以及 `event.group.name`、`event.group_name`；用 `getattr(..., None)` 逐层判空，最后 `str(x)[:128]`。**LangBot 不同版本字段名不一致，这就是为什么要探测而不是写死。**

`default.py` 的 IN 日志入队处补两键：

```python
sender_name, chat_name = peer_names(event)
deps.log_queue.enqueue({..., "senderName": sender_name, "chatName": chat_name})
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd QQMessageAssistantPython/qq_assistant_plugin && ../.venv/Scripts/python.exe -m pytest tests -q`
Expected: 全绿（原有 97 个 + 新增 2 个）

- [ ] **Step 5: 停下汇报**

---

### Task 9: Dify 工作流「记忆与整理」——digest 分支

**Files:**
- Create: `design/memory_workflow.md`（记录输入/输出契约与提示词全文）
- Dify 控制台操作（无法用代码完成，需要手工）

**Interfaces:**
- Consumes: Task 4 的 body 形状 `{inputs:{task, chat_name, messages, profile, focus}, response_mode:"blocking"}`。
- Produces: `outputs.items` —— JSON 字符串。

- [ ] **Step 1: 在 Dify 新建 Workflow 应用「记忆与整理」**

- Start 节点输入：`task`（文本）、`chat_name`（文本）、`messages`（文本，长）、`profile`（文本）、`focus`（文本）。
- 条件分支：`task == "digest"` 走整理；其余分支留空，后续计划再填。
- LLM 节点 → 代码节点（把模型输出包成 `items`）→ End 节点输出 `items`。

- [ ] **Step 2: 写提示词（全文进 `design/memory_workflow.md`）**

要点：只输出 JSON 数组；每项 `summary`（≤60 字，必须写明「谁说了什么」）、`importance` 1–5、`keywords`（≤3 个）、`mentions_me` 布尔、`source_msg_ids`（整数数组，**只能取自输入里每行开头的 id**）；**带 `[@我]` 的行必须各自产出一项，`mentions_me` 为 true 且 `importance` 不低于 4**；没有值得注意的内容就返回 `[]`；不许出现输入里没有的信息。
「值得注意」的判据按 `focus` 与 `profile` 调整；`focus` 为空时按「有时间的通知、要我做的事、提到我」三条兜底。

- [ ] **Step 3: 用样例跑通**

在 Dify 的 Run 面板粘贴 5–8 行假消息（含一条班会通知、一条闲聊），`task=digest`。
Expected: 返回合法 JSON 数组；班会那条 `importance >= 3` 且 `source_msg_ids` 指向真实 id。

- [ ] **Step 4: 记下 API Key 并配置**

Dify「访问 API」页取 Key，填进 `application.yaml` 对应的环境变量 `DIFY_WORKFLOW_API_KEY`（**不写进仓库**），`DIFY_WORKFLOW_BASE_URL` 指向该应用的 `/v1`。

- [ ] **Step 5: 停下汇报**

---

### Task 10: `/home` 首页与导航

**Files:**
- Create: `QQMessageAssistantVue/src/views/HomeView.vue`、`QQMessageAssistantVue/src/api/digest.ts`
- Modify: `QQMessageAssistantVue/src/router/index.ts`、`QQMessageAssistantVue/src/components/AppShell.vue`（`navItems` 与 `activeIndex`）

**Interfaces:**
- Consumes: `GET /api/digest?since=`、`GET /api/digest/last-seen`、`POST /api/digest/seen`。
- Produces: 路由 `/home`；`/` 改为重定向到 `/home`。

- [ ] **Step 1: 写 API 层**

```ts
export interface DigestItem {
  id: number; chatId: number; chatName: string | null; summary: string
  importance: number; mentionsMe: boolean; sourceMsgIds: string; createdAt: string
}
export function listDigest(since?: string) { return http.get<DigestItem[]>('/api/digest', { since }) }
export function getLastSeen() { return http.get<string>('/api/digest/last-seen') }
export function markSeen() { return http.post<number>('/api/digest/seen') }
```

- [ ] **Step 2: 建页面骨架**

`onMounted`：先 `getLastSeen()` 得到 `since`（空串 → 直接请求不带 `since`，后端默认 24h），再 `listDigest(since)`。加载中用 `<Skeleton>`（已有组件），失败用 `<ErrorState>`，空态给一句「这段时间群里没什么事」+ 指向 `/memos` 的链接。

列表按重要度分组：`importance >= 4` 的用深色卡片置顶，其余按时间。默认只渲染前 5 条，底部「都在这里了」展开全部并发一次 `markSeen()`。

- [ ] **Step 3: 改导航**

`AppShell.vue`：`navItems` 增加 `{ to: '/home', label: '首页', icon: Home }`（从 `lucide-vue-next` 引入），改成 5 项后 `grid-template-columns: repeat(5, 1fr)`、`.tab-indicator` 的 `width` 与 `translateX` 改为按 5 等分；`activeIndex` 增加 `/home → 0` 并顺移其余。`router/index.ts`：`{ path: '/', redirect: '/home' }`，新增 `/home` 路由（`meta.title = '首页'`）。

- [ ] **Step 4: 构建验证**

Run: `cd QQMessageAssistantVue && npm run build`
Expected: 零 TS 错误、构建成功

- [ ] **Step 5: 停下汇报**

---

### Task 11: 重要项弹窗（同一时段只弹一次）

**Files:**
- Create: `QQMessageAssistantVue/src/components/DigestPopup.vue`
- Modify: `QQMessageAssistantVue/src/views/HomeView.vue`
- Modify: `QQMessageAssistantVue/src/style.css`（若需要弹窗样式变量）

**Interfaces:**
- Consumes: `DigestItem[]`（Task 10）。
- Produces: `<DigestPopup :item="DigestItem | null" @close="..." />`。

- [ ] **Step 1: 实现弹窗**

用已有的 `<Sheet>` 组件（`src/components/Sheet.vue`）承载，不自己造遮罩。内容 = 群名 + 摘要 + 时间 + 「知道了」按钮。

- [ ] **Step 2: 去重逻辑**

`HomeView` 里：取 `items` 中 `importance >= 4` 的第一条；若 `localStorage.getItem('digest-popup:' + lastSeen)` 不存在，则展示并写入该键。`lastSeen` 为空时用 `items[0]?.createdAt` 兜底。**键里带 lastSeen**，保证「新的这段时间」会重新弹。

- [ ] **Step 3: 可访问性**

弹窗要有 `role="dialog"`、`aria-modal="true"`、打开时焦点进入、`Esc` 关闭、关闭后焦点回到触发元素。这三条来自 `ui-ux-pro-max` 的 `escape-routes` / `focus-management`。

- [ ] **Step 4: 构建验证**

Run: `cd QQMessageAssistantVue && npm run build`
Expected: 成功

- [ ] **Step 5: 停下汇报**

---

### Task 12: 群开关页

**Files:**
- Create: `QQMessageAssistantVue/src/api/chatSettings.ts`、`QQMessageAssistantVue/src/views/ChatSettingsView.vue`
- Modify: `QQMessageAssistantVue/src/router/index.ts`、`QQMessageAssistantVue/src/views/MoreView.vue`（加入口）

**Interfaces:**
- Consumes: `GET /api/chat-settings`、`PUT /api/chat-settings`。
- Produces: 路由 `/chat-settings`。

- [ ] **Step 1: API 层**

```ts
export interface ChatSetting {
  chatId: number; kind: 'GROUP' | 'PRIVATE'; chatName: string | null
  autoReplyEnabled: boolean; digestEnabled: boolean
}
export function listChatSettings() { return http.get<ChatSetting[]>('/api/chat-settings') }
export function putChatSetting(body: Partial<ChatSetting> & { chatId: number; kind: string }) {
  return http.put<ChatSetting>('/api/chat-settings', body)
}
```

- [ ] **Step 2: 页面**

列出所有群，每行两个开关（「整理」「自动回复」）。开关切换立刻 `PUT`，失败回滚并 toast。空态提示「机器人还没在任何群里说过话」——数据来自 `message_log` 的 `DISTINCT chat_id`，后端在 `GET` 里补齐未配置的群（本任务需回到 Task 3 的 Service，追加「把 `message_log` 里出现过的 group chat_id 但还没建行的群也列出来，`digestEnabled=false`」的逻辑与一条单测）。

- [ ] **Step 3: 入口**

`MoreView.vue` 增加一条「群设置」，跳 `/chat-settings`；`router/index.ts` 注册路由（`meta.title = '群设置'`）。

- [ ] **Step 4: 构建验证**

Run: `cd QQMessageAssistantVue && npm run build`
Expected: 成功

- [ ] **Step 5: 停下汇报**

---

## 真机验收（全部任务完成后）

1. 打开 `/home`，`app_setting.digest_enabled` 还是 `0` 时卡片应显示空态。
2. 把开关设为 `1`，在 `chat_setting` 里给一个测试群 `digest_enabled=1`，手动调 `DigestScheduler.runOnce`（或用 cron 等一小时）。
3. 群里发三条：一条 @ 机器人、一条普通通知、一条闲聊。
4. `/home` 刷新 → 三条进整理结果；@ 我 的那条 `importance >= 4` 并弹窗。
5. 点「都在这里了」→ 再刷新不重复出现；`last_digest_seen` 已更新。
6. 把该群 `digest_enabled` 改回 `0` → 已有行不再出现在 `/api/digest` 里。
7. 停掉 Dify → 下一次整理任务只记 `log.warn`，Spring 不崩、`/home` 正常打开。
