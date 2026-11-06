# 群 / 私聊命名草稿与展示别名 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新群/私聊首次进入系统时生成命名草稿，用户设置的名字在所有展示位置替代 QQ 号，并把回复草稿的对方名字一并带出。

**Architecture:** 新增独立的 `chat_alias` 表保存会话展示名；消息入库时触发命名草稿，启动时补发存量；草稿确认写入别名；来源格式化、召回标签、整理卡片在 Java 侧统一按「别名第一个 > 已有群名/昵称 > 号码」解析。Python 插件只负责把对方昵称/群名写进回复草稿 payload，前端负责命名输入与筛选。

**Tech Stack:** Spring Boot 3.4.12 / Java 21 / MyBatis-Plus / MySQL；Vue3 + TypeScript + Vite；Python 3.12 插件；JUnit 5 + Mockito + AssertJ。

**Spec:** `docs/superpowers/specs/2026-11-04-chat-naming-drafts-design.md`

## Global Constraints

- 业务逻辑留在 Spring Boot；Dify 只做语义判断，不碰持久化。
- 不改 Dify 提示词 / DSL；别名在 Java 侧解析后再送模型。
- 群号与个人 QQ 可能相同：所有别名读写必须带 `kind ∈ {GROUP, PRIVATE}`。
- 建命名草稿失败不能影响消息入库（只记日志）。
- `contact` 表、`/api/contacts`、问答反问认人保留；只删前端「认一个」手动入口。
- 前端沿用 Composition API `<script setup>` 与现有组件/样式变量。
- 本仓库没有 `mvnw`，使用系统 `mvn`；Java 命令在 `QQMessageAssistantJava/` 下执行。
- 不提交 git（用户未要求）；每个任务以测试通过作为完成标志。

## Review Focus

- 私聊草稿 `draft.chat_id` 为 `null`：确认时必须从 `payload.chatId` 取会话键，否则别名写到 `null` 或写错会话。
- 群号恰好等于某人的 QQ：缺少 `kind` 会串会话；测试必须覆盖同号不同 kind。
- 新会话连发多条消息：只能生成一条命名草稿，重复触发不能刷屏。
- 用户拒绝命名后：同一会话不能下次再来一条草稿。
- 已有群名/昵称但没有别名：必须回落显示原名字，不能变成空。

---

### Task 1: `chat_alias` 持久化与解析

**Files:**
- Create: `QQMessageAssistantJava/src/main/resources/db/migrations/2026-11-04-chat-alias.sql`
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/entity/ChatAlias.java`
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/mapper/ChatAliasMapper.java`
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/ChatAliasService.java`
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/impl/ChatAliasServiceImpl.java`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/service/ChatAliasServiceImplTest.java`

**Interfaces:**
- Produces:
  - `ChatAliasService.aliases(Long chatId, String kind) -> List<String>`
  - `ChatAliasService.displayName(Long chatId, String kind) -> String`（无别名返回 `null`）
  - `ChatAliasService.displayNames() -> Map<String, String>`（键 `kind + ":" + chatId`）
  - `ChatAliasService.saveAliases(Long chatId, String kind, String aliases) -> void`
  - `ChatAliasService.hasAliasRow(Long chatId, String kind) -> boolean`
  - `ChatAliasService.ensureNamingDraft(Long chatId, String kind, String suggestedName, Long senderId) -> void`
  - `ChatAliasService.backfillNamingDrafts() -> int`
  - `ChatAliasService.key(Long chatId, String kind) -> String`（静态）
- `ChatAliasServiceImpl` 构造器注入 `ChatAliasMapper`、`DraftMapper`、`MessageLogMapper`。

- [ ] **Step 1: 写迁移 SQL**

```sql
CREATE TABLE IF NOT EXISTS `chat_alias` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `chat_id`    BIGINT UNSIGNED NOT NULL COMMENT '群号；私聊存对方 QQ',
  `kind`       VARCHAR(8)  NOT NULL COMMENT 'GROUP|PRIVATE',
  `aliases`    VARCHAR(255) NOT NULL DEFAULT '' COMMENT '逗号分隔；空串 = 用户拒绝命名',
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_alias` (`chat_id`, `kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话展示名';
```

- [ ] **Step 2: 写失败测试**

`ChatAliasServiceImplTest`（Mockito）至少覆盖：

```java
@Test void aliases_splitsTrimsAndDropsBlanks()
@Test void displayName_usesTheFirstAlias()
@Test void saveAliases_dedupesAndTruncatesTo255()
@Test void saveAliases_upsertsExistingRow()
@Test void hasAliasRow_distinguishesKindsWithSameNumber()
@Test void displayNames_keysByKindAndChatId()
```

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn -o test -Dtest=ChatAliasServiceImplTest`
Expected: 编译失败 / 类不存在。

- [ ] **Step 4: 实现 Entity / Mapper / Service**

`ChatAlias` 字段：`id, chatId, kind, aliases, createdAt, updatedAt`。
`aliases(Long, String)`：查 `(chatId, kind)`，按逗号切分、trim、去空、保持顺序去重。
`displayName`：`aliases` 第一个或 `null`。
`saveAliases`：清洗后写库；已有行更新 `aliases/updatedAt`，否则插入；`aliases` 为空串也允许保存（表示拒绝命名）。
`displayNames`：全表查询，键 `key(chatId, kind)`，值取第一个别名。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -o test -Dtest=ChatAliasServiceImplTest`
Expected: BUILD SUCCESS，0 failures。

---

### Task 2: 新会话触发命名草稿 + 启动补发

**Files:**
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/impl/MessageLogServiceImpl.java`
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/ChatAliasBackfillRunner.java`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/impl/ChatAliasServiceImpl.java`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/service/ChatAliasDiscoveryTest.java`

**Interfaces:**
- Consumes: Task 1 的 `ChatAliasService.ensureNamingDraft/backfillNamingDrafts/hasAliasRow`。
- Produces: `MessageLogServiceImpl.batchSave` 对每条成功写入的 `IN` 消息调用 `ensureNamingDraft`。
- 草稿形状：`type=NAMING`；群聊 `draft.chatId=群号`，私聊 `draft.chatId=null`；`payload={chatId, kind, suggestedName}`；`title="给群 {id} 起个名字"` / `"给 QQ {id} 起个名字"`。

- [ ] **Step 1: 写失败测试**

`ChatAliasDiscoveryTest`（Mockito 注入 `ChatAliasMapper`、`DraftMapper`、`MessageLogMapper`）：

```java
@Test void groupMessageFromNewChatCreatesOneNamingDraft()
@Test void privateMessageUsesPeerIdAsChatKeyAndNullDraftChatId()
@Test void outMessageDoesNotCreateNamingDraft()
@Test void existingAliasRowSkipsDraft()
@Test void existingNamingDraftSkipsSecondOne()
@Test void sameNumberDifferentKindDoesNotCollide()
@Test void backfillCreatesDraftsForSeenGroupAndPrivatePeers()
@Test void backfillSkipsChatsThatAlreadyHaveAliasOrDraft()
```

再给 `MessageLogServiceImpl` 加测试：新群 IN 消息会调用 `ensureNamingDraft`；`ensureNamingDraft` 抛异常时 `batchSave` 仍返回保存条数。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -o test -Dtest=ChatAliasDiscoveryTest`
Expected: FAIL（`ensureNamingDraft` 未实现 / 未调用）。

- [ ] **Step 3: 实现触发与补发**

`ChatAliasServiceImpl.ensureNamingDraft`：

1. `chatId == null || kind` 非 GROUP/PRIVATE → 直接返回。
2. `hasAliasRow` → 返回。
3. 查 `draft` 是否存在 `type=NAMING` 且 payload 中 `chatId+kind` 匹配的任意状态记录 → 返回。
   - 用 `DraftMapper.selectList` 查 `type=NAMING`，在内存里比对 `payload.chatId/kind`（数据量小，避免依赖 JSON 查询）。
4. 插入命名草稿（字段见 Interfaces）。

`MessageLogServiceImpl`：注入 `ChatAliasService`；在 `save(messageLog)` 成功后，若 `direction=IN`，按 `chatId != null ? (chatId,GROUP) : (senderId,PRIVATE)` 调用 `ensureNamingDraft`，整段 try/catch 只记 WARN。

`ChatAliasBackfillRunner`：`@Component` + `@EventListener(ApplicationReadyEvent.class)`，调用 `backfillNamingDrafts()`，异常只记日志。

`backfillNamingDrafts`：遍历 `messageLogMapper.selectGroupPeers()` 与 `selectPrivatePeers()`，逐个 `ensureNamingDraft`，返回新建草稿数。`ensureNamingDraft` 改为返回 `void`，补发内部自行计数：补发前记录草稿数或在方法内返回 boolean 私有方法。实现时用私有 `boolean ensureNamingDraftInternal(...)`，公开方法委托，补发累加 `true` 次数。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -o test -Dtest=ChatAliasDiscoveryTest,MessageLogServiceTest`（若无 `MessageLogServiceTest`，把新增用例放在 `ChatAliasDiscoveryTest`）
Expected: BUILD SUCCESS。

---

### Task 3: `NAMING` 草稿类型与确认

**Files:**
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/dto/DraftConfirmRequest.java`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/impl/DraftServiceImpl.java`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/DraftService.java`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/controller/DraftController.java`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/dto/DraftCreateRequest.java`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/service/DraftNamingTest.java`

**Interfaces:**
- Consumes: Task 1 `ChatAliasService.saveAliases`。
- Produces:
  - `DraftService.confirmDraft(Long id, String aliases)`；保留 `confirmDraft(Long id)` 委托为 `confirmDraft(id, null)`。
  - `POST /api/drafts/{id}/confirm` 接受可选 body `{"aliases":"小明,明明"}`。
  - `TYPES` 包含 `NAMING`。

- [ ] **Step 1: 写失败测试**

`DraftNamingTest`（Mockito，mock `ChatAliasService`、`LangBotClient` 等 `DraftServiceImpl` 依赖）：

```java
@Test void confirmNamingDraftSavesAliasesAndConfirms()
@Test void confirmNamingDraftRejectsBlankAliases()
@Test void confirmNamingDraftUsesPayloadChatIdAndKind()
@Test void rejectNamingDraftDoesNotWriteAlias()
@Test void confirmReplyStillIgnoresAliasesArgument()
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -o test -Dtest=DraftNamingTest`
Expected: FAIL（`NAMING` 不被接受 / 方法不存在）。

- [ ] **Step 3: 实现**

- `DraftServiceImpl.TYPES` 加 `NAMING`；`DraftCreateRequest` 的 `allowableValues` 加 `NAMING`。
- `confirmDraft(Long id, String aliases)`：`isPending` 后，如果是 `NAMING`：
  - `aliases == null || aliases.isBlank()` → `BusinessException("先给它起个名字")`。
  - 从 `payload.get("chatId")` / `payload.get("kind")` 取会话键，缺一个即报错。
  - 调 `chatAliasService.saveAliases(...)`，并把 `aliases` 写回 `payload`。
  - 取消 `dispatch(draft)` 对 `NAMING` 的调用（写入别名本身就是分发结果）。
- 其他类型走原 `dispatch`。
- `rejectDraft` 不变；NAMING 的「不再追问」由 Task 2 的「任意状态草稿即跳过」保证。
- Controller：`confirmDraft(@PathVariable Long id, @RequestBody(required=false) DraftConfirmRequest body)`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -o test -Dtest=DraftNamingTest,DraftDispatchTest`
Expected: BUILD SUCCESS。

---

### Task 4: 展示替换（后端）

**Files:**
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/retrieval/SourceFormat.java`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/retrieval/RetrievalServiceImpl.java`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/impl/AskServiceImpl.java`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/impl/ChatDigestServiceImpl.java`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/impl/ChatSettingServiceImpl.java`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/entity/ChatSetting.java`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/service/ChatAliasDisplayTest.java`
- Modify tests: `ChatDigestQueryTest`、`ChatDigestServiceImplTest`、`ChatSettingServiceImplTest`、`RetrievalServiceTest`

**Interfaces:**
- Consumes: Task 1 `displayName/displayNames`。
- Produces:
  - `SourceFormat.messageSource(MessageLog row, String displayName)`；保留 `messageSource(MessageLog)` 委托为 `null`。
  - `ChatSetting` 新增 `@TableField(exist=false) private String aliases;`
  - `ChatDigestServiceImpl` / `ChatSettingServiceImpl` / `RetrievalServiceImpl` / `AskServiceImpl` 构造器新增 `ChatAliasService`。

- [ ] **Step 1: 写失败测试**

`ChatAliasDisplayTest`：

```java
@Test void groupSourceUsesAliasInsteadOfNumber()
@Test void privateSourceUsesAliasInsteadOfQQ()
@Test void sourceFallsBackToChatNameWhenAliasMissing()
@Test void sourceFallsBackToNumberWhenNothingElse()
```

补 `ChatDigestQueryTest`：给 `ChatAliasService` mock 返回别名，断言 `DigestItemVO.chatName` 已替换。
补 `ChatSettingServiceImplTest`：`listAll` 返回的 `ChatSetting.aliases` 是第一个别名。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -o test -Dtest=ChatAliasDisplayTest,ChatDigestQueryTest,ChatSettingServiceImplTest`
Expected: FAIL。

- [ ] **Step 3: 实现**

- `SourceFormat`：群聊 `displayName != null ? displayName : (chatName or "群 id")`；私聊 `displayName != null ? displayName : "QQ id"`。
- `RetrievalServiceImpl`：构造器加 `ChatAliasService`；`retrieve()` 开头取一次 `displayNames()`，`messageCandidate` 用它生成 `label` 与 `source`。`label` 别名存在时用 `"群 " + alias` / `"QQ " + alias`，否则维持旧格式。更新 `RetrievalServiceTest` 的手工构造。
- `AskServiceImpl`：构造器加 `ChatAliasService`；`loadNoteSources` 调用 `formatSources(found, displayNames())`，`formatSources` 接收 map 并按 `chatId != null ? GROUP : PRIVATE` 取别名。
- `ChatDigestServiceImpl`：构造器加 `ChatAliasService`；`listSince` 映射 VO 时用 `displayName(chatId, "GROUP")` 覆盖 `chatName`；`digestChat` 的 `runDigest` 与落库行用别名优先的 `chatName`。
- `ChatSettingServiceImpl`：构造器/字段注入 `ChatAliasService`；`listAll` 给每行（含 discovered）设置 `aliases = displayName(chatId, kind)`。
- 更新所有受构造器影响的测试。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -o test -Dtest=ChatAliasDisplayTest,ChatDigestQueryTest,ChatDigestServiceImplTest,ChatSettingServiceImplTest,RetrievalServiceTest,AskServiceTest,AskMultiRoundTest,AskSingleRoundTest`
Expected: BUILD SUCCESS。

---

### Task 5: 回复草稿带上对方名字（Python）

**Files:**
- Modify: `QQMessageAssistantPython/qq_assistant_plugin/components/event_listener/default.py`
- Test: `QQMessageAssistantPython/qq_assistant_plugin/tests/test_event_listener.py`

**Interfaces:**
- `_respond(deps, ctx, is_group, sender_id, chat_id, text, event_type, file_ctx=None, sender_name="", chat_name="")`
- REPLY 草稿 payload 新增 `sourceSenderName`、`sourceChatName`。

- [ ] **Step 1: 写失败测试**

在 `test_event_listener.py` 现有回复草稿用例中断言：

```python
assert draft.args[4]["sourceSenderName"] == "小张"
assert draft.args[4]["sourceChatName"] == "三班群"  # 群聊
```

- [ ] **Step 2: 运行测试确认失败**

Run: `QQMessageAssistantPython/.venv/Scripts/python.exe -m pytest QQMessageAssistantPython/qq_assistant_plugin/tests/test_event_listener.py -q`
Expected: FAIL（KeyError / 值缺失）。

- [ ] **Step 3: 实现**

`_handle` 里已算出 `sender_name, chat_name`，两处 `_respond(...)` 调用都传入。`create_draft` 的 payload 增加两个字段。私聊 `chat_name` 为空串。

- [ ] **Step 4: 运行测试确认通过**

Run: `QQMessageAssistantPython/.venv/Scripts/python.exe -m pytest QQMessageAssistantPython/qq_assistant_plugin/tests -q`
Expected: 全部通过。

---

### Task 6: 前端（草稿筛选、命名输入、展示、删除认一个）

**Files:**
- Modify: `QQMessageAssistantVue/src/api/types.ts`
- Modify: `QQMessageAssistantVue/src/api/drafts.ts`
- Modify: `QQMessageAssistantVue/src/api/chatSettings.ts`
- Modify: `QQMessageAssistantVue/src/utils/meta.ts`
- Modify: `QQMessageAssistantVue/src/views/DraftsView.vue`
- Modify: `QQMessageAssistantVue/src/components/DraftItem.vue`
- Modify: `QQMessageAssistantVue/src/views/ChatSettingsView.vue`
- Modify: `QQMessageAssistantVue/src/views/MemoryView.vue`

**Interfaces:**
- Consumes: `POST /api/drafts/{id}/confirm` 可选 `{aliases}`；`GET /api/chat-settings` 的 `aliases`。

- [ ] **Step 1: 更新类型与 API**

- `DraftType` 加 `'NAMING'`。
- `ChatSetting` 加 `aliases?: string | null`。
- `confirmDraft(id: number, body?: { aliases?: string })`。

- [ ] **Step 2: 草稿页类型筛选**

`DraftsView` 在状态筛选下再加一排类型筛选：`全部 / 回复 / 命名 / 其他`（`REPLY`、`NAMING`、其余）。两排筛选同时生效；空态文案按当前筛选调整。

- [ ] **Step 3: 命名草稿卡片**

`DraftItem`：
- `ACTION_LABEL.NAMING = '保存名字'`，`IRREVERSIBLE` 不含 `NAMING`；`draftTypeLabel` / 图标补 `NAMING`（用 `Tag`）。
- `NAMING` 渲染输入框（预填 `payload.suggestedName`，占位「多个名字用逗号分隔，例如：三班群,班群」），以及「保存名字」「不用了」。
- `resolve('confirm')` 时把输入值传给 `confirmDraft(id, { aliases })`；空值本地报错不请求。
- REPLY 的「对方」区显示 `payload.sourceSenderName`：`对方 · 小张`，下一行才是 `sourceContent`；没有名字时保持 `对方`。

- [ ] **Step 4: 会话设置页与 Memory 页**

- `ChatSettingsView.chatLabel`：`chat.aliases || chat.chatName || 群/QQ 兜底`。
- `MemoryView`：删除「认一个」按钮、`Sheet` 表单、`openContactSheet/saveContact/createContact` 相关代码与 import；空态文案改为「夜间归纳会自动认人」。

- [ ] **Step 5: 构建验证**

Run: `cd QQMessageAssistantVue && npm run build`
Expected: exit 0，`✓ built`。

---

### Task 7: 全量验证与人工验收说明

- [ ] **Step 1: 后端全量测试**

Run: `cd QQMessageAssistantJava && mvn -o test`
Expected: `Failures: 0, Errors: 0`，BUILD SUCCESS。

- [ ] **Step 2: 前端构建**

Run: `cd QQMessageAssistantVue && npm run build`
Expected: exit 0。

- [ ] **Step 3: 插件测试**

Run: `QQMessageAssistantPython/.venv/Scripts/python.exe -m pytest QQMessageAssistantPython/qq_assistant_plugin/tests -q`
Expected: 全部通过。

- [ ] **Step 4: 人工验收（告知用户，不代跑）**

1. 执行迁移 `db/migrations/2026-11-04-chat-alias.sql`。
2. 重启后端 → 草稿中心出现存量群/私聊的命名草稿。
3. 保存名字 → 首页整理卡片、问答证据出处、会话设置页显示名字。
4. 新群发一条消息 → 只出现一条命名草稿；拒绝后不再出现。
