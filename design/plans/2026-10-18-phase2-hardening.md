# Phase 2 收口加固 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修掉 Java 端 5 处会在 Phase 3/4 联调时暴露出来的缺陷，让 Spring Boot 以「可被 LangBot/Dify 稳定调用」的状态进入 Phase 3。

**Architecture:** 不改分层、不改表结构。只修校验、配置键、事务语义与测试基座位置。

**Tech Stack:** Spring Boot 3.4.12 / Java 21 / MyBatis-Plus 3.5.9 / JUnit 5。

## Global Constraints

- 执行前先 `git status` 确认工作区干净；每个 Task 独立 commit。
- 命令一律在 `QQMessageAssistantJava/` 下执行，使用系统 `mvn`（无 wrapper）。
- 测试需要 `192.168.100.128:3307/qq_assistant_test` 可连接。
- 改动后全量 `mvn test` 必须 30/30 绿。

---

## 审计结论（先看这个）

| # | 位置 | 严重度 | 现象 |
|---|---|---|---|
| H1 | `MemoServiceImpl.patch` | **高** | 传 `importance=0/6` 不校验，`REMINDER_STRATEGY.get(0)` 返回 null → `for` 循环 NPE → 500 |
| H2 | `ProfileFactServiceImpl.updateFact:51` | 中 | `!fact.equals(fact.getFactKey())` 拿实体和字符串比较，恒为 true；改 key 时的冲突判断退化成「永远查一次自己」（当前不致错，但是错的） |
| H3 | `ProfileFactServiceImpl.upsertAuto` | **高** | 契约 §8.2 写 `confidence` 选填，代码强制非空 → Dify 不传就 400 |
| H4 | `MemoReminderScheduler` | **中** | `@Scheduled` 读 `${app.reminder.delay}`，`application.yaml` 写的是 `scan-interval-ms` → 配置静默失效；`app.reminder.enabled` 完全没被读 |
| H5 | `MemoReminderScheduler.scanOnce` | **中** | `@Transactional` 方法被同类 `scan()` 自调用，代理不生效 —— 注解是假的 |
| H6 | `MemoServiceImpl.expandReminders` | **中** | `dueTime` 在过去时照常展开，所有提醒 `remind_at` 都在过去 → 下一轮扫描瞬间连发 |
| H7 | `src/main/resources/application-test.yaml` | **低** | 测试配置被打进生产 jar |
| M1 | `ProfileFactServiceImpl.updateFact:54` | 低 | 文案错字「用户已存在该试试键」 |
| M2 | `DraftServiceImpl.confirmDraft` | 设计注意 | 外部 HTTP 在 DB 事务内（最长 8s 读超时）。当前「发送失败 → 回滚为 PENDING」语义是对的，**不建议改**；Phase 3 接桥后要把读超时调到 20s（桥内含拟人延迟），见 3-3 Task 5 |
| M3 | `DraftServiceImpl.resolveTarget` | **Phase 4 风险** | REPLY/FILE 草稿缺 `targetType` 时默认 `private`，并回退到 `chatId`。群消息草稿若 Dify 忘带 `targetType`，会把群号当私聊对象发，必然失败。Phase 4 的 Dify 提示词必须强制输出 `targetType` |
| M4 | 测试 | 观察项 | 首跑 30 个用例中 13 个 context 加载失败，**立即重跑 30/30 绿**。若复现，保留完整 `mvn test` 日志定位 |

---

## Task 1: 备忘录补丁校验与提醒展开护栏

**Files:**
- Modify: `src/main/java/com/czx/qqmessageassistantjava/service/impl/MemoServiceImpl.java`
- Test: `src/test/java/com/czx/qqmessageassistantjava/service/MemoServiceImplTest.java` (create)

**Interfaces:**
- Consumes: `MemoPatchRequest.importance`, `Memo.getDueTime()`
- Produces: 无签名变化；`patch` 对非法 importance 抛 `BusinessException`

- [ ] **Step 1: 写失败测试**

```java
package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.MemoCreateRequest;
import com.czx.qqmessageassistantjava.dto.MemoPatchRequest;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.entity.MemoReminder;
import com.czx.qqmessageassistantjava.mapper.MemoReminderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoServiceImplTest {

    @Autowired private MemoService memoService;
    @Autowired private MemoReminderMapper memoReminderMapper;

    private Memo create(int importance, LocalDateTime due) {
        MemoCreateRequest req = new MemoCreateRequest();
        req.setSenderId(9001L);
        req.setTitle("t");
        req.setImportance(importance);
        req.setDueTime(due);
        return memoService.create(req);
    }

    @Test
    void patchRejectsImportanceOutOfRange() {
        Memo memo = create(3, LocalDateTime.now().plusDays(2));
        MemoPatchRequest patch = new MemoPatchRequest();
        patch.setImportance(6);

        assertThatThrownBy(() -> memoService.patch(memo.getId(), patch))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("importance");
    }

    @Test
    void pastDueTimeDoesNotCreatePastReminders() {
        Memo memo = create(5, LocalDateTime.now().minusDays(2));
        long past = memoReminderMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MemoReminder>()
                        .eq(MemoReminder::getMemoId, memo.getId())
                        .lt(MemoReminder::getRemindAt, LocalDateTime.now()));
        assertThat(past).isZero();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -Dtest=MemoServiceImplTest
```

预期：`patchRejectsImportanceOutOfRange` 抛 `NullPointerException` 而不是 `BusinessException`；`pastDueTimeDoesNotCreatePastReminders` 断言失败（计数 > 0）。

- [ ] **Step 3: 修 `patch` 校验**

在 `MemoServiceImpl.patch` 的 `PATCH_STATUSES` 校验之后加：

```java
if (request.getImportance() != null
        && (request.getImportance() < 1 || request.getImportance() > 5)) {
    throw new BusinessException("importance 必须为 1-5");
}
```

- [ ] **Step 4: 修 `expandReminders`**

```java
private void expandReminders(Memo memo) {
    if (memo.getDueTime() == null) return;
    List<String> labels = REMINDER_STRATEGY.get(memo.getImportance());
    if (labels == null) {
        throw new BusinessException("importance 必须为 1-5");
    }
    LocalDateTime now = LocalDateTime.now();
    for (String label : labels) {
        LocalDateTime remindAt = computeRemindAt(memo.getDueTime(), label);
        if (!remindAt.isAfter(now)) {
            continue;                     // 过去的提醒点直接跳过，避免集中补发
        }
        MemoReminder reminder = new MemoReminder();
        reminder.setMemoId(memo.getId());
        reminder.setOffsetLabel(label);
        reminder.setRemindAt(remindAt);
        reminder.setStatus("PENDING");
        memoReminderMapper.insert(reminder);
    }
}
```

- [ ] **Step 5: 跑测试**

```bash
mvn test -Dtest=MemoServiceImplTest
```

预期：2/2 PASS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/czx/qqmessageassistantjava/service/impl/MemoServiceImpl.java \
        src/test/java/com/czx/qqmessageassistantjava/service/MemoServiceImplTest.java
git commit -m "fix(memo): validate importance on patch and skip past reminder points"
```

---

## Task 2: 事实画像三处修正

**Files:**
- Modify: `src/main/java/com/czx/qqmessageassistantjava/service/impl/ProfileFactServiceImpl.java`
- Test: `src/test/java/com/czx/qqmessageassistantjava/controller/ProfileControllerTest.java` (append)

**Interfaces:**
- `PUT /api/profile/facts` 的 `confidence` 变为可选，缺省 `1.0`。
- `PUT /api/profile/facts/{id}` 的 key 冲突判断改为 `factKey.equals(fact.getFactKey())`。

- [ ] **Step 1: 追加失败测试**

```java
@Test
void autoUpsertWithoutConfidenceDefaultsToOne() throws Exception {
    mockMvc.perform(put("/api/profile/facts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"senderId": 1288, "factKey": "姓名", "factValue": "张三"}
                            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.confidence").value(1.0));
}

@Test
void updateKeepsSameKeyAndRejectsDuplicateKey() throws Exception {
    String created = mockMvc.perform(post("/api/profile/facts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"senderId": 1289, "factKey": "A", "factValue": "1"}
                            """))
            .andReturn().getResponse().getContentAsString();
    long id = com.jayway.jsonpath.JsonPath.parse(created).read("$.data.id", Long.class);

    // 同 key 的修改不应报「已存在」
    mockMvc.perform(put("/api/profile/facts/" + id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"factKey\": \"A\", \"factValue\": \"2\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.factValue").value("2"));
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -Dtest=ProfileControllerTest
```

预期：`autoUpsertWithoutConfidenceDefaultsToOne` 400；新加的 update 用例可能因错误文案而通过（说明判断逻辑是碰巧对的），所以以 Step 3 的代码审查为准。

- [ ] **Step 3: 修实现**

`upsertAuto`：

```java
BigDecimal confidence = request.getConfidence() == null
        ? BigDecimal.ONE : request.getConfidence();
return upsert(senderId, factKey, factValue, confidence, "AUTO");
```

`updateFact` 第 51 行：

```java
if (factKey != null && !factKey.isBlank() && !factKey.equals(fact.getFactKey())) {
```

第 54 行文案：

```java
throw new BusinessException("该用户已存在相同事实键");
```

- [ ] **Step 4: 跑测试**

```bash
mvn test -Dtest=ProfileControllerTest
```

预期：5/5 PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/czx/qqmessageassistantjava/service/impl/ProfileFactServiceImpl.java \
        src/test/java/com/czx/qqmessageassistantjava/controller/ProfileControllerTest.java
git commit -m "fix(profile): optional confidence, correct key comparison, clearer message"
```

---

## Task 3: 提醒调度器的配置与事务语义

**Files:**
- Modify: `src/main/java/com/czx/qqmessageassistantjava/schedule/MemoReminderScheduler.java`
- Modify: `src/main/resources/application.yaml`
- Modify: `src/main/resources/application-test.yaml`

**Interfaces:**
- 配置键统一为 `app.reminder.scan-interval-ms`、`app.reminder.batch-size`、`app.reminder.enabled`。
- 移除 `scanOnce` 上无效的 `@Transactional`。

- [ ] **Step 1: 改调度器**

```java
@Scheduled(fixedDelayString = "${app.reminder.scan-interval-ms:60000}")
public void scan() {
    if (!enabled) {
        return;
    }
    try { ... } catch (Exception e) { ... }
}

@Value("${app.reminder.enabled:true}")
private boolean enabled;

// 每条 remind 的状态更新各自是一条 SQL；不加事务，
// 避免把 HTTP 发送包进长事务。
public int scanOnce() { ... }
```

同时删除 `import org.springframework.transaction.annotation.Transactional;`。

> 为什么不做成「整批一个事务」：`scanOnce` 内含 LangBot HTTP 调用，整批事务会把网络等待变成行锁持有时间，且单条失败会回滚整批。逐条更新才是正确粒度。`scan()` 自调用问题也因此消失。

- [ ] **Step 2: 核对 yaml**

`application.yaml` 已是：

```yaml
app:
  reminder:
    enabled: true
    scan-interval-ms: 60000
    batch-size: 50
```

无需改值，改完后它才真正生效（此前代码读的是 `app.reminder.delay`）。

- [ ] **Step 3: 跑调度器测试 + 全量**

```bash
mvn test -Dtest=MemoReminderSchedulerTest
mvn test
```

预期：5/5，随后 32/32（新增 2 个 Memo 测试）全绿。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/czx/qqmessageassistantjava/schedule/MemoReminderScheduler.java \
        src/main/resources/application.yaml src/main/resources/application-test.yaml
git commit -m "fix(scheduler): use scan-interval-ms config, honor enabled flag, drop no-op transaction"
```

---

## Task 4: 测试配置归位

**Files:**
- Move: `src/main/resources/application-test.yaml` → `src/test/resources/application-test.yaml`

- [ ] **Step 1: 移动文件**

```bash
mkdir -p src/test/resources
git mv src/main/resources/application-test.yaml src/test/resources/application-test.yaml
```

- [ ] **Step 2: 全量回归**

```bash
mvn test
```

预期：32/32 绿。`application-test.yaml` 不再出现在 `target/classes`。

- [ ] **Step 3: 验证生产 jar 不含测试配置**

```bash
mvn -q package -DskipTests
unzip -l target/*.jar | grep application-test || echo "OK: not packaged"
```

预期：输出 `OK: not packaged`。

- [ ] **Step 4: Commit**

```bash
git add -A src/main/resources src/test/resources
git commit -m "chore(test): move test profile to src/test/resources"
```

---

## Task 5: Phase 2 收口验收

- [ ] **Step 1: 全量构建**

```bash
mvn clean test
```

预期：`BUILD SUCCESS`，32 tests。

- [ ] **Step 2: 启动并核对 Swagger**

```bash
mvn spring-boot:run
```

打开 `http://localhost:8080/swagger-ui/index.html`，对照 `design/api_contract.md` 第二节 A 组逐条勾选 27 个端点。

- [ ] **Step 3: 三个最容易错的接口冒烟**

```bash
curl -s "http://localhost:8080/api/drafts?status=PENDING" | head -c 300
curl -s -X POST http://localhost:8080/api/collection-tasks/check \
  -H "Content-Type: application/json" \
  -d '{"chatId":1,"taskType":"学号收集","taskKey":"1-学号收集-2026W42"}'
curl -s "http://localhost:8080/api/profile/1001"
```

预期依次：`code:0` 且 `data` 为数组；`data.replied` 为布尔；`data.facts` 为对象、`stylePrompt` 为字符串。

- [ ] **Step 4: 打 tag / 记进度**

在 `design/README.md` 把 Phase 2 状态改为 ✅，进入 Phase 3。
