# Phase B：夜间归纳 + 历史查询 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让「一句话问回去」可用——每天 0:10 把当天消息抽成 `contact` / `memory_note`，前端 `/ask` 用自然语言提问，认不出人时反问，答案必须带得回原文证据。

**Architecture:** 语义归 Dify（抽人、抽事实、答题），数据与校验归 Spring。`ask` 分两段跑：`stage=resolve` 只抽人名，Spring 拿别名去 `contact` 表查（0 条=不认识，≥2 条=冲突，都转成反问）；`stage=answer` 给候选集，Spring 校验 `used_ids ⊆ 候选集`，对不上就降级成「只给证据」。

**Tech Stack:** Java 21 / Spring Boot 3.4.12 / MyBatis-Plus / RestClient；Dify Workflow（blocking）；Vue3 + TypeScript + Vite。

**Spec:** `design/core_features.md` §5.3、§7、§9。契约细节写入 `design/agent_contract.md` 前先改 `design/api_contract.md`（仓库规矩：任何一侧改字段，先改契约文件）。

## Global Constraints

- 结果统一 `Result{code,message,data}`；业务拒绝抛 `BusinessException` → 200 + `code!=0`。
- 新表 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`，主键 `BIGINT UNSIGNED AUTO_INCREMENT`。
- 迁移只增不删：`db/migrations/` 新文件 + `db/schema.sql` 同时改，两个库（`qq_assistant`、`qq_assistant_test`）都要执行。
- **不自动提交 git。**
- 时间列由 Java 写本地时间，不用 MySQL 的 `CURRENT_TIMESTAMP`（那台库跑 UTC，见 `design/memory_workflow.md` 末尾）。
- 定时任务默认关，由 `app_setting.memory_job_enabled = "1"` 打开。
- 证据 id 一律带类型前缀：`msg:` / `note:` / `memo:` / `file:`。
- 前端零 UI 库，沿用现有 CSS 变量与 `<script setup>`；`npm run build` 必须零错误。
- 插件测试在插件目录下跑：`cd QQMessageAssistantPython/qq_assistant_plugin && ../.venv/Scripts/python.exe -m pytest tests -q`。

## Review Focus

1. **同一个别名对应两个 sender_id** → 必须反问，不能挑一个（Task 5）。
2. **模型给的 `used_ids` 里有候选集之外的 id** → 结论作废、只留证据（Task 5）。
3. **凌晨任务跑到一半崩了** → 游标停在上一批，下次从这里继续，不重跑已处理的消息（Task 3）。
4. **用户认过的人（`source='USER'`）被夜间任务覆盖** → 绝不允许（Task 2）。
5. **某个群的昵称/群名一直是空的** → 归纳仍要跑，不能因为名字缺失整批失败（Task 3）。

---

### Task 1: 建表与迁移

**Files:**
- Create: `QQMessageAssistantJava/src/main/resources/db/migrations/2026-10-30-phase8-memory.sql`
- Modify: `QQMessageAssistantJava/src/main/resources/db/schema.sql`（文件末尾追加两张表）

**Interfaces:**
- Produces: 表 `contact`、`memory_note`。

- [ ] **Step 1: 写迁移脚本**

```sql
-- Phase 8：历史查询的数据底座（人物表 + 事实归纳）
ALTER TABLE `message_log` ADD KEY `idx_sender_created_id` (`sender_id`, `created_at`, `id`);

CREATE TABLE IF NOT EXISTS `contact` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id`  BIGINT UNSIGNED NOT NULL,
  `alias`      VARCHAR(64)  NOT NULL COMMENT '用户/模型认出来的称呼',
  `relation`   VARCHAR(64)  NULL COMMENT '同学|老师|室友…',
  `importance` TINYINT      NOT NULL DEFAULT 0 COMMENT '0=普通 1=一般 2=重要',
  `note`       VARCHAR(255) NULL,
  `source`     VARCHAR(8)   NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO|USER',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sender_alias` (`sender_id`, `alias`),
  KEY `idx_alias` (`alias`),
  KEY `idx_sender` (`sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='认识的人';

CREATE TABLE IF NOT EXISTS `memory_note` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `subject_sender_id` BIGINT UNSIGNED NULL COMMENT '主体；空=关于主人自己',
  `predicate`         VARCHAR(64)  NOT NULL COMMENT '受控词表',
  `value`             VARCHAR(255) NOT NULL,
  `happened_at`       DATETIME     NULL,
  `source_msg_ids`    VARCHAR(512) NOT NULL COMMENT '逗号分隔的 message_log.id',
  `confidence`        DECIMAL(3,2) NULL,
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_subject_pred` (`subject_sender_id`, `predicate`),
  KEY `idx_predicate` (`predicate`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='事实归纳';
```

> `memory_note` 不加 `UNIQUE(subject,predicate,value)`：`subject_sender_id` 可空，MySQL 唯一索引允许多个 NULL，去重交给代码（Task 3），并避免长 `value` 撑爆索引。

- [ ] **Step 2: 同步 `db/schema.sql`**（粘贴同样两张表；`message_log` 段补 `KEY idx_sender_created_id`）

- [ ] **Step 3: 在两个库上执行迁移并核对**

```bash
mysql -h192.168.100.128 -P3307 -uroot -proot qq_assistant      < .../phase8-memory.sql
mysql -h192.168.100.128 -P3307 -uroot -proot qq_assistant_test < .../phase8-memory.sql
```
Expected: `SHOW TABLES LIKE 'contact'` / `'memory_note'` 各一行；`SHOW INDEX FROM memory_note` 有 `idx_subject_pred`。

---

### Task 2: `contact` 的读写

**Files:**
- Create: `entity/Contact.java`、`mapper/ContactMapper.java`、`service/ContactService.java`、`service/impl/ContactServiceImpl.java`、`dto/ContactRequest.java`、`vo/ContactVO.java`、`controller/ContactController.java`
- Test: `src/test/java/.../service/ContactServiceTest.java`

**Interfaces:**
- Produces:
  - `List<ContactVO> ContactService.list(Long senderId)`（`senderId` 为空=全部，按 `alias` 升序）
  - `Contact ContactService.upsertAuto(Long senderId, String alias, String relation)` — 已存在且 `source='USER'` 时**不改**；`AUTO` 行只更新非空 `relation`
  - `Contact ContactService.addUser(Long senderId, String alias, String relation)` — 存在则更新并置 `source='USER'`
  - `List<Contact> ContactService.findByAlias(String alias)` — 精确匹配，用于认人
  - `void ContactService.delete(Long id)`
  - HTTP：`GET /api/contacts?senderId=`、`POST /api/contacts`、`DELETE /api/contacts/{id}`

- [ ] **Step 1: 写失败测试**

```java
@SpringBootTest @ActiveProfiles("test") @Transactional
class ContactServiceTest {
    @Autowired ContactService contactService;
    private static final Long SENDER = 9_910_001L;

    @Test void upsertAuto_neverOverwritesAUserRow() {
        contactService.addUser(SENDER, "小明", "同学");
        contactService.upsertAuto(SENDER, "小明", "隔壁班的");
        assertThat(contactService.findByAlias("小明"))
                .singleElement()
                .satisfies(c -> { assertThat(c.getSource()).isEqualTo("USER");
                                  assertThat(c.getRelation()).isEqualTo("同学"); });
    }

    @Test void upsertAuto_isIdempotentForTheSameAlias() { /* 两次调用只留一行，relation 更新 */ }

    @Test void findByAlias_returnsEverySenderUsingThatName() {
        contactService.addUser(9_910_002L, "小明", "室友");
        contactService.addUser(9_910_003L, "小明", "同学");
        assertThat(contactService.findByAlias("小明")).extracting(Contact::getSenderId)
                .containsExactlyInAnyOrder(9_910_002L, 9_910_003L);
    }
}
```

- [ ] **Step 2: 跑测试确认失败** — `mvn test -Dtest=ContactServiceTest`，Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现**

`upsertAuto` 用 `lambdaQuery().eq(senderId).eq(alias).one()` 判断；`ContactRequest` 全字段 `@NotBlank` 校验放在 service（仓库用 `BusinessException` 而不是 Bean Validation）。`alias` 超 64 字、`relation` 超 64 字一律 `BusinessException("称呼太长")`。

- [ ] **Step 4: 跑测试** — Expected: PASS 3/3。

- [ ] **Step 5: 停下汇报**（不提交）

---

### Task 3: 夜间归纳 `MemoryJobService`

**Files:**
- Create: `entity/MemoryNote.java`、`mapper/MemoryNoteMapper.java`、`service/MemoryJobService.java`、`service/impl/MemoryJobServiceImpl.java`
- Test: `src/test/java/.../service/MemoryJobServiceTest.java`

**Interfaces:**
- Consumes: `ContactService.upsertAuto`、`DifyWorkflowClient.run("extract", …)`、`AppSettingService`
- Produces: `int MemoryJobService.runOnce()` —— 处理游标之后的一批消息，返回写入的 note 条数（0 = 没料或没开）
- `app_setting` 键：`memory_job_enabled`（`"1"` 才跑）、`memory_job_cursor`（最后处理的 `message_log.id`）

**Dify 契约（task=extract）**
- 输入：`chat_name`、`messages`（每行 `id|sender_id|昵称|HH:mm|正文`，正文压换行、截 200 字，一批最多 200 条）、`known_contacts`（JSON：`[{"senderId":"123","alias":"小明","relation":"同学"}]`，最多 200 条）、`owner_aliases`
- 输出：`contacts`（字符串 JSON 数组）、`notes`（字符串 JSON 数组）

```json
{"contacts":[{"sender_id":"123","alias":"小明","relation":"同学"}],
 "notes":[{"subject_sender_id":"123","predicate":"生日","value":"11月11日","source_msg_ids":[5]}]}
```

- [ ] **Step 1: 写失败测试**（真库 + `@MockitoBean DifyWorkflowClient`）

```java
@Test void runOnce_writesNotesAndAdvancesTheCursor()      // 2 条消息 → 1 条 note + 1 个 contact；游标 = 最大 id
@Test void runOnce_dropsNoteWhoseEvidenceIsOutsideTheBatch()  // source_msg_ids=[999999] → 不写
@Test void runOnce_skipsANoteWhoseValueAlreadyExists()    // 同一 (subject,predicate,value) 第二次不写
@Test void runOnce_isNoOpWhenTheSwitchIsOff()             // verifyNoInteractions(difyWorkflowClient)
```

- [ ] **Step 2: 跑测试确认失败** — Expected: 编译失败。

- [ ] **Step 3: 实现**

要点：
- 查询 `id > cursor AND direction = 'IN'`，`orderByAsc(id)`，`last("LIMIT 200")`；
- 批内的 `sender_id` 集合与 `id` 集合用于校验，**任何一项越界整条丢弃**；
- `predicate` 不在词表 → 落 `其他`；词表：`生日/姓名/专业/班级/学号/住址/电话/时间安排/偏好/关系/其他`；
- 同一批里重复的 `(subject,predicate,value)` 也去重；
- 日志打印「本批 N 条消息 → M 个 contact / K 条 note」；
- 游标在**写库成功后**才推进（同一事务内更新 `app_setting`）；
- `messages` 为空时不调 Dify，也不推进游标以外的任何东西。

- [ ] **Step 4: 跑测试** — Expected: PASS 4/4。

- [ ] **Step 5: 停下汇报**

---

### Task 4: 夜间调度 + 手动补算

**Files:**
- Create: `schedule/MemoryScheduler.java`、`controller/MemoryController.java`
- Test: `src/test/java/.../schedule/MemorySchedulerTest.java`

**Interfaces:**
- Consumes: `MemoryJobService.runOnce()`
- Produces:
  - `MemoryScheduler.runOnce()` — `@Scheduled(cron = "0 10 0 * * *")`，内部先看开关
  - `POST /api/memory/rebuild` → `Result<Integer>`，重置 `memory_job_cursor=0` 后循环 `runOnce()`，最多 50 批，返回处理的 note 总数

- [ ] **Step 1: 写失败测试**

```java
@Test void runOnce_isNoOpWhenDisabled()  // memory_job_enabled=0 → verifyNoInteractions(memoryJobService)
@Test void rebuild_resetsCursorAndDrainsInBatches()
    // 三次 runOnce 返回 2,3,0 → 总计 5，游标被先置 0
```

- [ ] **Step 2: 跑测试确认失败** — Expected: 编译失败。

- [ ] **Step 3: 实现**（`rebuild` 同步循环；注释写明「个人库，几批就完了，不引入异步线程」）

- [ ] **Step 4: 跑测试** — Expected: PASS 2/2。

- [ ] **Step 5: 停下汇报**

---

### Task 5: `/api/ask` —— 认人、候选集、答题、校验

**Files:**
- Create: `dto/AskRequest.java`、`dto/ClarifyAnswer.java`、`vo/AskResponse.java`、`vo/EvidenceVO.java`、`vo/ClarifyCandidateVO.java`、`service/AskService.java`、`service/impl/AskServiceImpl.java`、`controller/AskController.java`
- Test: `src/test/java/.../service/AskServiceTest.java`

**Interfaces:**
- Consumes: `ContactService.findByAlias/addUser`、`MemoryNoteMapper`、`MessageLogMapper`、`MemoMapper`、`FileTaskMapper`、`DifyWorkflowClient.run("ask", …)`
- Produces: `AskResponse AskService.ask(AskRequest request)`

请求/响应（**先写进 `design/api_contract.md`**）：

```json
{"question":"我的同学小明的生日是什么时候","clarify":null}
{"question":"…","clarify":{"alias":"小明","senderId":"123456","from":"就是三班群里那个"}}
```

```json
{"answer":"小明的生日是 11 月 11 日。","degraded":false,
 "evidence":[{"type":"msg","id":"123","label":"三班群 · 10-28 21:03 · 小明","content":"11 月 11"}],
 "needClarify":null}
```

```json
{"answer":"","degraded":false,"evidence":[],
 "needClarify":{"alias":"小明","candidates":[{"senderId":"123456","relation":"同学","hint":"三班群"}]}}
```

**Dify 契约（task=ask）**
- `stage=resolve` 输入：`question`、`known_contacts`、`owner_aliases`
  输出：`entities`（人名 JSON 数组）、`keywords`（JSON 数组）、`time_hint`（text）
- `stage=answer` 输入：`question`、`candidates`（每行 `type:id|标签|正文`，总长 ≤ 12000 字）、`time_hint`
  输出：`answer`（text）、`used_ids`（JSON 数组，如 `["msg:123"]`）

**候选集**（Spring，SQL）：解析出的每个人 → `memory_note`（主体=他，≤30）、`message_log`（发送者=他，≤30）；`keywords` → `memory_note.predicate/value LIKE`、`message_log.content LIKE`（≤30）、`memo`（标题/正文 LIKE，≤15）、`file_task`（文件名 LIKE，≤15）。去重后按「note > msg > memo > file」拼装。

- [ ] **Step 1: 写失败测试**（真库 + `@MockitoBean DifyWorkflowClient`）

```java
@Test void unknownAlias_returnsNeedClarifyAndNeverCallsTheAnswerStage()
@Test void twoSendersShareAnAlias_returnsNeedClarifyWithBothCandidates()
@Test void clarifyWritesAUserContactAndThenAnswers()      // 第二轮不再反问
@Test void usedIdsOutsideTheCandidateSet_degradeToEvidenceOnly()  // answer 为空，evidence 仍返回，degraded=true
@Test void resolvedEntity_feedsHisNotesAndMessagesAsCandidates()  // 断言发给 Dify 的 candidates 含 note:/msg:
```

- [ ] **Step 2: 跑测试确认失败** — Expected: 编译失败。

- [ ] **Step 3: 实现**

规则：
- `question` 空白 → `BusinessException("问题不能为空")`；
- `clarify` 非空时先 `addUser(clarify.senderId, clarify.alias, "")`，并把该别名直接映射到该 sender，跳过 DB 歧义；
- 反问只返回第一处未解决的别名（`needClarify` 是单数），用户答完再问下一处；
- `used_ids` 每个值都必须出现在候选集 id 集合里，**任何一项越界整次降级**（`answer=""`、`degraded=true`），evidence 按候选顺序最多 5 条返回；
- Dify 返回解析失败 → 抛 `BusinessException("没能理解这次查询的结果")`，不要把原文塞给前端。

- [ ] **Step 4: 跑测试** — Expected: PASS 5/5。

- [ ] **Step 5: 停下汇报**

---

### Task 6: Dify 工作流加 `extract` / `ask` 分支

**Files:**
- Modify: `design/QQAssistant-memory-workflow.yml`
- Modify: `design/memory_workflow.md`（§1 契约、§3 图结构、§4 提示词、§5 体检项）
- Modify: `QQMessageAssistantPython/qq_assistant_plugin/tests/test_memory_workflow_dsl.py`

**图结构**

```
start ─► is_digest ─true─► digest_llm ─► parse_digest ─► end_digest(items)
                 └false─► is_extract ─true─► extract_llm ─► parse_extract ─► end_extract(contacts,notes)
                                  └false─► is_resolve ─true─► resolve_llm ─► parse_resolve ─► end_resolve(entities,keywords,time_hint)
                                                    └false─► answer_llm ─► parse_ask_answer ─► end_answer(answer,used_ids)
```

- Start 变量新增：`stage`、`question`、`known_contacts`、`owner_aliases`
- 结束节点输出变量名跨应用唯一：`items` / `contacts`,`notes` / `entities`,`keywords`,`time_hint` / `answer`,`used_ids`
- 删除原来的 `empty_items` + `end_other`（`task` 只会有三种值，旧占位分支没有调用方）

- [ ] **Step 1: 先改测试（RED）**

在 `test_memory_workflow_dsl.py` 增加：
- Start 变量集合 == `{task, chat_name, messages, profile, focus, stage, question, known_contacts, owner_aliases}`
- 四条分支的边接线齐全（8 条边）
- 结束节点输出名互不重复，且恰好是上面 9 个
- `parse_extract` / `parse_resolve` / `parse_ask_answer` 三个代码节点可执行：脏输入返回空形状、`used_ids` 不是数组时返回 `[]`

Run: `pytest tests/test_memory_workflow_dsl.py -q`  Expected: FAIL（变量集合对不上）。

- [ ] **Step 2: 改 YAML 到 GREEN**，重跑 Expected: PASS。

- [ ] **Step 3: 更新 `design/memory_workflow.md`** 的调用契约与提示词，并补一句「ask 分两段跑」的理由。

- [ ] **Step 4: 停下汇报**

---

### Task 7: 前端 `/ask` 与「认识的人」

**Files:**
- Create: `QQMessageAssistantVue/src/api/ask.ts`、`QQMessageAssistantVue/src/views/AskView.vue`
- Modify: `QQMessageAssistantVue/src/router/index.ts`（`/ask`）、`src/views/MoreView.vue`（入口卡）、`src/views/MemoryView.vue`（认识的人区块）
- Test: `npm run build`（前端无测试框架，如实记录）

**Interfaces:**
- `ask(question, clarify?) → AskResponse`；`listContacts(senderId?)`、`createContact({senderId,alias,relation})`、`deleteContact(id)`

- [ ] **Step 1: 写 `api/ask.ts`**（类型与后端 VO 一一对应）
- [ ] **Step 2: 写 `AskView.vue`**：单输入框 + 消息流；`needClarify` 渲染成候选卡片，点选后带 `clarify` 重发原问题；`degraded` 时顶部提示「没找到能对上的原文，只列了候选」；evidence 每条显示 `label` + `content`
- [ ] **Step 3: 路由 + MoreView 入口**（「问一句」）
- [ ] **Step 4: MemoryView 加「认识的人」**：列表（称呼 / QQ / 关系 / 来源徽标）+ 新增 + 删除
- [ ] **Step 5: `npm run build`**  Expected: 零错误。

- [ ] **Step 6: 停下汇报**

---

### Task 8: 文档同步

**Files:**
- Modify: `design/api_contract.md`（`/api/ask`、`/api/contacts`、`/api/memory/rebuild`）
- Modify: `design/database_schema.md`（`contact`、`memory_note`）
- Modify: `design/agent_contract.md`（新增 §5「整理/归纳/查询的 Workflow 契约」指向 `memory_workflow.md`）

- [ ] **Step 1: 补三份文档**
- [ ] **Step 2: 全量验证**

```bash
cd QQMessageAssistantJava && mvn test
cd QQMessageAssistantPython/qq_assistant_plugin && ../.venv/Scripts/python.exe -m pytest tests -q
cd QQMessageAssistantVue && npm run build
```

- [ ] **Step 3: 停下汇报（附三套结果）**
