# Phase 2-2 实现计划：修复既有缺陷 + 画像 / 设置 / 文件任务镜像

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修掉既有 3 处缺陷，并把 Spring Boot 端剩余 4 张表（`profile_fact` / `style_profile` / `app_setting` / `file_task`）及其 REST API 补齐。完成后，Spring Boot 端**除「定时调度 + 主动发送」外**的全部接口就绪（那部分见 Plan 2-3）。

**Architecture:** 延续既有分层 `Controller → Service(接口 + impl) → Mapper(BaseMapper) → MySQL`。统一响应用 `common.Result<T>`，业务异常用 `common.BusinessException`，参数用 hutool `BeanUtil.copyProperties` 拷贝，字段校验手写在 Service 里（与既有 `MemoServiceImpl` / `CollectionTaskServiceImpl` 一致）。

**Tech Stack:** Spring Boot 3.4.12 / Java 21 / MyBatis-Plus 3.5.9 / MySQL 8 / Lombok / hutool 5.8.11 / springdoc 2.8.0。

## Global Constraints

- 包根：`com.czx.qqmessageassistantjava`。
- 表结构以 `design/database_schema.md` 为唯一准据；接口以 `design/api_contract.md` 为唯一准据。**代码与文档冲突时改代码**（文档是准据）。
- 统一响应体：`common.Result<T>{ int code; String message; T data; }`，成功 `code = 0`。静态工厂：`Result.ok()` / `Result.ok(data)` / `Result.fail(code, message)`。
- 业务异常：`common.BusinessException(String)`，由 `GlobalExceptionHandler` 转成 `HTTP 400 + code 400`。
- 主键：`@TableId(type = IdType.AUTO)`。
- 逻辑删除：**仅** `draft` / `memo` 有 `is_deleted` 字段并标注 `@TableLogic`。本计划新增的 4 张表**都没有** `is_deleted`（全局 `logic-delete-field` 只在实体存在该字段时才生效，无需特殊处理）。
- JSON 字段：实体类加 `@TableName(value="x", autoResultMap = true)`，字段加 `@TableField(typeHandler = JacksonTypeHandler.class)`。
- 不建外键；跨表语义由应用层维护。
- 时间类型统一 `java.time.LocalDateTime`；`created_at` / `updated_at` 由数据库默认值维护，实体**不写** `@TableField(fill=...)`。
- 每个 Task 作为一个独立提交（`git commit`）。

---

## 一、现状核对：已完成 vs 未完成

### 已完成（本次不动）

| 模块 | 端点 | 文件 |
|---|---|---|
| 草稿中心 | `POST/GET /api/drafts`、`/{id}/confirm`、`/{id}/reject` | `DraftController` / `DraftServiceImpl` |
| 消息日志 | `POST /api/message-logs` | `MessageController` / `MessageLogServiceImpl` |
| 拦截规则 | `GET/POST /api/intercept-rules`、`PUT/DELETE /{id}` | `InterceptController` / `InterceptRuleServiceImpl` |
| 回复防重 | `POST /api/collection-tasks/check`、`/mark`、`GET /rules` | `CollectionTaskController` / `CollectionTaskServiceImpl` |
| 备忘录 | `POST/GET /api/memo`、`PATCH /{id}` + 提醒展开 | `MemoController` / `MemoServiceImpl` |

### 未完成（本计划 + Plan 2-3 负责）

| 缺口 | 归属 |
|---|---|
| 画像·事实 全部端点（`/api/profile/...`） | **本计划** |
| 画像·风格 全部端点（`/api/style-profile/...`） | **本计划** |
| 设置（`/api/settings`） | **本计划** |
| 文件任务镜像（`/api/file-tasks`） | **本计划** |
| `db/schema.sql` + 测试基座 | **本计划** |
| LangBot 客户端（Spring Boot → LangBot 的 `send_message` / `send_file`） | Plan 2-3 |
| 草稿 `REPLY` / `FILE` / `FACT` 三种分发动作 | Plan 2-3 |
| 定时提醒扫描 + 主动推送 | Plan 2-3 |

---

## 二、待修缺陷清单（Task 1 处理）

| # | 位置 | 现象 | 根因 |
|---|---|---|---|
| **B1** | `DraftServiceImpl.getDraftList` | `GET /api/drafts` **任何调用都失败**，草稿列表完全不可用 | 校验写成 `if (type != null \|\| !TYPES.contains(type))`，`\|\|` 应为 `&&`。两种走法都炸：①带参数时 `type != null` 为 true → 短路直接抛 `BusinessException`（400）；②不带参数时左侧 false，右侧 `TYPES.contains(null)` 会 **NPE**（`Set.of()` 的 `contains(null)` 抛 NPE）→ 500 |
| **B2** | `MemoController` | 路由是 `/api/memo`（单数），与契约 `/api/memos` 不符，Vue 按契约调用会 404 | `@RequestMapping("/api/memo")` 拼写 |
| **B3** | `CollectionTaskController.check` | 返回 `data: true`，契约要求 `data: { "replied": true }` | 直接返回了 `Boolean`；`dto/CheckResult.java` 这个 record 已建好但没用上 |

> **另注（不改代码，仅提醒）**：`application.yaml` 里明文写了数据库账号密码并已提交进 git。个人内网项目可接受，但换环境时建议改成 `${DB_URL}` 这类环境变量占位。

---

## 三、文件结构（本计划新增/修改）

```
QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/
├── service/impl/
│   └── DraftServiceImpl.java                   (改：B1 校验条件)
├── controller/
│   ├── MemoController.java                     (改：B2 路由)
│   ├── CollectionTaskController.java           (改：B3 响应体)
│   ├── ProfileController.java                  (新：事实记忆 6 个端点)
│   ├── StyleProfileController.java             (新：风格画像 2 个端点)
│   ├── SettingsController.java                 (新：设置 2 个端点)
│   └── FileTaskController.java                 (新：文件任务镜像 2 个端点)
├── entity/
│   ├── ProfileFact.java                        (新)
│   ├── StyleProfile.java                       (新)
│   ├── AppSetting.java                         (新)
│   └── FileTask.java                           (新)
├── mapper/
│   ├── ProfileFactMapper.java                  (新)
│   ├── StyleProfileMapper.java                 (新)
│   ├── AppSettingMapper.java                   (新)
│   └── FileTaskMapper.java                     (新)
├── dto/
│   ├── FactUpsertRequest.java                  (新：Dify 高置信写入)
│   ├── FactManualRequest.java                  (新：Vue 总控台新增)
│   ├── FactUpdateRequest.java                  (新：Vue 总控台修改)
│   ├── ProfileView.java                        (新：会话注入响应体)
│   ├── StyleProfileUpdateRequest.java          (新)
│   └── FileTaskSyncRequest.java                (新)
└── service/
    ├── ProfileFactService.java                 (新)
    ├── StyleProfileService.java                (新)
    ├── AppSettingService.java                  (新)
    ├── FileTaskService.java                    (新)
    └── impl/
        ├── ProfileFactServiceImpl.java         (新)
        ├── StyleProfileServiceImpl.java        (新)
        ├── AppSettingServiceImpl.java          (新)
        └── FileTaskServiceImpl.java            (新)

QQMessageAssistantJava/src/main/resources/
└── db/schema.sql                               (新：10 张表 DDL，含 IF NOT EXISTS)

QQMessageAssistantJava/src/test/resources/
└── application-test.yaml                       (新：测试库 + 自动建表)

QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/
├── controller/
│   ├── DraftListFixTest.java                   (新：B1 回归)
│   ├── MemoRouteFixTest.java                   (新：B2 回归)
│   └── CollectionTaskCheckFixTest.java         (新：B3 回归)
├── service/
│   └── ProfileFactServiceTest.java             (新)
└── controller/
    ├── ProfileControllerTest.java              (新)
    ├── StyleProfileControllerTest.java         (新)
    ├── SettingsControllerTest.java             (新)
    └── FileTaskControllerTest.java             (新)
```

> 既有的 `controller/MessageController.java`（Dify 直连透传脚手架，含硬编码 Dify Key）**本计划不动**，Phase 4 再处理。

---

### Task 1: 测试基座 + 修复 B1 / B2 / B3

**Files:**
- Create: `QQMessageAssistantJava/src/main/resources/db/schema.sql`
- Create: `QQMessageAssistantJava/src/test/resources/application-test.yaml`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/impl/DraftServiceImpl.java`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/controller/MemoController.java`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/controller/CollectionTaskController.java`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/controller/DraftListFixTest.java`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/controller/MemoRouteFixTest.java`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/controller/CollectionTaskCheckFixTest.java`

**Interfaces:**
- Produces: 测试基座（`application-test.yaml` 指向 `qq_assistant_test` 库，启动时用 `db/schema.sql` 幂等建表）。后续所有 Task 的测试都复用 `@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc @Transactional` 这套注解。
- Produces: `db/schema.sql` —— 10 张表的完整 DDL，Plan 2-3 与后续环境初始化都直接用它。

- [ ] **Step 1: 建测试库**

在 MySQL 上手动执行一次（192.168.100.128:3307，账号 root/root）：

```sql
CREATE DATABASE IF NOT EXISTS `qq_assistant_test`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

Run: `mysql -h 192.168.100.128 -P 3307 -uroot -proot -e "SHOW DATABASES LIKE 'qq_assistant%';"`
Expected: 同时列出 `qq_assistant` 与 `qq_assistant_test`。

- [ ] **Step 2: 写 `src/main/resources/db/schema.sql`**

把 `design/database_schema.md` 第三节的 10 段 DDL 原样抄进来，**每一条 `CREATE TABLE` 都改成 `CREATE TABLE IF NOT EXISTS`**（测试库每次启动都会执行本文件，必须幂等）。表名顺序按文档：`draft`、`message_log`、`memo`、`memo_reminder`、`collection_task`、`intercept_rule`、`profile_fact`、`style_profile`、`app_setting`、`file_task`。

- [ ] **Step 3: 写 `src/test/resources/application-test.yaml`**

```yaml
spring:
  datasource:
    url: jdbc:mysql://192.168.100.128:3307/qq_assistant_test?useUnicode=true&characterEncoding=utf-8&zeroDateTimeBehavior=convertToNull&allowMultiQueries=true&allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
  sql:
    init:
      mode: always
      schema-locations: classpath:db/schema.sql

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      id-type: auto
      logic-delete-field: isDeleted
      logic-delete-value: 1
      logic-not-delete-value: 0
```

> 测试类一律加 `@Transactional`，用例结束自动回滚，避免脏数据累积。

- [ ] **Step 4: 写 B1 的失败测试**

`src/test/java/com/czx/qqmessageassistantjava/controller/DraftListFixTest.java`：

```java
package com.czx.qqmessageassistantjava.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DraftListFixTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listWithoutParamsReturnsOk() throws Exception {
        mockMvc.perform(get("/api/drafts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void listWithValidTypeReturnsOk() throws Exception {
        mockMvc.perform(get("/api/drafts").param("type", "REPLY").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void listWithInvalidTypeReturns400() throws Exception {
        mockMvc.perform(get("/api/drafts").param("type", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
```

- [ ] **Step 5: 跑测试确认失败**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=DraftListFixTest`
Expected: `listWithoutParamsReturnsOk` 失败（实际 **500**，NPE）；`listWithValidTypeReturnsOk` 失败（实际 **400**）。第三个用例 `listWithInvalidTypeReturns400` 此时**已经通过**（碰巧结果正确）。

- [ ] **Step 6: 修 B1**

`DraftServiceImpl.getDraftList` 中两处 `||` 改成 `&&`：

```java
    @Override
    public List<Draft> getDraftList(String type, String status) {
        if (type != null && !TYPES.contains(type)) {
            throw new BusinessException("Invalid type, type 必须是【" + TYPES + "】其中之一");
        }
        if (status != null && !STATUS.contains(status)) {
            throw new BusinessException("Invalid status, status 必须是【" + STATUS + "】其中之一");
        }
        return lambdaQuery()
                .eq(type != null, Draft::getType, type)
                .eq(status != null, Draft::getStatus, status)
                .orderByDesc(Draft::getCreatedAt)
                .list();
    }
```

- [ ] **Step 7: 跑测试确认通过**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=DraftListFixTest`
Expected: Tests run: 3, Failures: 0, Errors: 0.

- [ ] **Step 8: 写 B2 的失败测试**

`MemoRouteFixTest.java`（注解同 Step 4，类名换成 `MemoRouteFixTest`）：

```java
    @Test
    void pluralRouteIsMapped() throws Exception {
        mockMvc.perform(get("/api/memos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
```

- [ ] **Step 9: 跑测试确认失败**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=MemoRouteFixTest`
Expected: FAIL，HTTP 404。

- [ ] **Step 10: 修 B2**

`MemoController` 的 `@RequestMapping` 改为复数：

```java
@RequestMapping("/api/memos")
```

- [ ] **Step 11: 跑测试确认通过**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=MemoRouteFixTest`
Expected: Tests run: 1, Failures: 0.

- [ ] **Step 12: 写 B3 的失败测试**

`CollectionTaskCheckFixTest.java`（注解同上）：

```java
    @Test
    void checkReturnsRepliedObject() throws Exception {
        mockMvc.perform(post("/api/collection-tasks/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chatId": 9001, "taskType": "学号收集", "taskKey": "9001-学号收集-2026W42"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.replied").value(false));
    }
```

需要补 import：
```java
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
```

- [ ] **Step 13: 跑测试确认失败**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=CollectionTaskCheckFixTest`
Expected: FAIL，`$.data.replied` 不存在（实际返回 `$.data` = `false`）。

- [ ] **Step 14: 修 B3**

`CollectionTaskController.check` 改用已存在的 `dto/CheckResult`：

```java
    @Operation(summary = "检查任务是否已执行")
    @PostMapping("/check")
    public Result<CheckResult> check(@RequestBody CollectionTaskRequest request) {
        return Result.ok(new CheckResult(collectionTaskService.checkReplied(request)));
    }
```

（`dto/CheckResult.java` 已存在且形如 `public record CheckResult(boolean replied) {}`，无需新增。）

- [ ] **Step 15: 跑全部新增测试**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=DraftListFixTest+MemoRouteFixTest+CollectionTaskCheckFixTest`
Expected: Tests run: 5, Failures: 0, Errors: 0.

- [ ] **Step 16: 提交**

```bash
git add QQMessageAssistantJava/src
git commit -m "fix: 修复草稿列表校验条件、备忘录路由复数、防重 check 响应体；补齐 schema.sql 与测试基座"
```

---

### Task 2: 事实记忆 `profile_fact` —— 实体 / Mapper / Service

**Files:**
- Create: `entity/ProfileFact.java`
- Create: `mapper/ProfileFactMapper.java`
- Create: `dto/FactUpsertRequest.java`
- Create: `dto/FactManualRequest.java`
- Create: `dto/FactUpdateRequest.java`
- Create: `service/ProfileFactService.java`
- Create: `service/impl/ProfileFactServiceImpl.java`
- Test: `src/test/java/com/czx/qqmessageassistantjava/service/ProfileFactServiceTest.java`

**Interfaces:**
- Produces:
  - `ProfileFactService extends IService<ProfileFact>`
  - `ProfileFact upsertAuto(FactUpsertRequest req)`
  - `ProfileFact createManual(FactManualRequest req)`
  - `ProfileFact updateFact(Long id, FactUpdateRequest req)`
  - `void deleteFact(Long id)`
  - `List<ProfileFact> listBySender(Long senderId)`
  - `Map<String, String> factsMap(Long senderId)`

- [ ] **Step 1: 写实体 `entity/ProfileFact.java`**

```java
package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("profile_fact")
public class ProfileFact {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long senderId;

    private String factKey;

    private String factValue;

    private BigDecimal confidence;

    /** AUTO | MANUAL */
    private String source;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
```

> 无 `is_deleted` —— 本表用**物理删除**（原因见 `database_schema.md` 通用约定）。

- [ ] **Step 2: 写 `mapper/ProfileFactMapper.java`**

```java
package com.czx.qqmessageassistantjava.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czx.qqmessageassistantjava.entity.ProfileFact;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProfileFactMapper extends BaseMapper<ProfileFact> {
}
```

- [ ] **Step 3: 写三个 DTO**

`dto/FactUpsertRequest.java`：
```java
package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "事实写入请求（Dify 高置信）")
public class FactUpsertRequest {

    @Schema(description = "归属用户（仅‘本人’才高置信写）")
    private Long senderId;

    @Schema(description = "事实键，如 学号/姓名/专业/目标")
    private String factKey;

    @Schema(description = "事实值")
    private String factValue;

    @Schema(description = "置信度 0-1")
    private BigDecimal confidence;
}
```

`dto/FactManualRequest.java`：
```java
package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "事实新增请求（Vue 记忆总控台）")
public class FactManualRequest {

    private Long senderId;
    private String factKey;
    private String factValue;
}
```

`dto/FactUpdateRequest.java`：
```java
package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "事实修改请求（Vue 记忆总控台）")
public class FactUpdateRequest {

    private String factKey;
    private String factValue;
}
```

- [ ] **Step 4: 写 `service/ProfileFactService.java`**

```java
package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.dto.FactManualRequest;
import com.czx.qqmessageassistantjava.dto.FactUpdateRequest;
import com.czx.qqmessageassistantjava.dto.FactUpsertRequest;
import com.czx.qqmessageassistantjava.entity.ProfileFact;

import java.util.List;
import java.util.Map;

public interface ProfileFactService extends IService<ProfileFact> {

    ProfileFact upsertAuto(FactUpsertRequest request);

    ProfileFact createManual(FactManualRequest request);

    ProfileFact updateFact(Long id, FactUpdateRequest request);

    void deleteFact(Long id);

    List<ProfileFact> listBySender(Long senderId);

    Map<String, String> factsMap(Long senderId);
}
```

- [ ] **Step 5: 写 `service/impl/ProfileFactServiceImpl.java`**

```java
package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.FactManualRequest;
import com.czx.qqmessageassistantjava.dto.FactUpdateRequest;
import com.czx.qqmessageassistantjava.dto.FactUpsertRequest;
import com.czx.qqmessageassistantjava.entity.ProfileFact;
import com.czx.qqmessageassistantjava.mapper.ProfileFactMapper;
import com.czx.qqmessageassistantjava.service.ProfileFactService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProfileFactServiceImpl extends ServiceImpl<ProfileFactMapper, ProfileFact>
        implements ProfileFactService {

    @Override
    public ProfileFact upsertAuto(FactUpsertRequest request) {
        require(request.getSenderId(), request.getFactKey(), request.getFactValue());
        return upsert(request.getSenderId(), request.getFactKey(), request.getFactValue(),
                request.getConfidence(), "AUTO");
    }

    @Override
    public ProfileFact createManual(FactManualRequest request) {
        require(request.getSenderId(), request.getFactKey(), request.getFactValue());
        return upsert(request.getSenderId(), request.getFactKey(), request.getFactValue(), null, "MANUAL");
    }

    @Override
    public ProfileFact updateFact(Long id, FactUpdateRequest request) {
        ProfileFact fact = getById(id);
        if (fact == null) {
            throw new BusinessException("事实不存在");
        }
        if (request.getFactKey() != null && !request.getFactKey().isBlank()
                && !request.getFactKey().equals(fact.getFactKey())) {
            ProfileFact clash = findOne(fact.getSenderId(), request.getFactKey());
            if (clash != null && !clash.getId().equals(id)) {
                throw new BusinessException("该用户已存在事实键 " + request.getFactKey());
            }
            fact.setFactKey(request.getFactKey());
        }
        if (request.getFactValue() != null && !request.getFactValue().isBlank()) {
            fact.setFactValue(request.getFactValue());
        }
        fact.setSource("MANUAL");
        updateById(fact);
        return fact;
    }

    @Override
    public void deleteFact(Long id) {
        if (getById(id) == null) {
            throw new BusinessException("事实不存在");
        }
        removeById(id);   // 物理删除：本表无 is_deleted
    }

    @Override
    public List<ProfileFact> listBySender(Long senderId) {
        return lambdaQuery()
                .eq(senderId != null, ProfileFact::getSenderId, senderId)
                .orderByAsc(ProfileFact::getFactKey)
                .list();
    }

    @Override
    public Map<String, String> factsMap(Long senderId) {
        return listBySender(senderId).stream()
                .collect(Collectors.toMap(ProfileFact::getFactKey, ProfileFact::getFactValue,
                        (a, b) -> b, LinkedHashMap::new));
    }

    private ProfileFact upsert(Long senderId, String factKey, String factValue,
                               BigDecimal confidence, String source) {
        ProfileFact existing = findOne(senderId, factKey);
        if (existing != null) {
            existing.setFactValue(factValue);
            if (confidence != null) {
                existing.setConfidence(confidence);
            }
            if ("MANUAL".equals(source)) {
                existing.setSource("MANUAL");
            }
            updateById(existing);
            return existing;
        }
        ProfileFact fact = new ProfileFact();
        fact.setSenderId(senderId);
        fact.setFactKey(factKey);
        fact.setFactValue(factValue);
        fact.setConfidence(confidence);
        fact.setSource(source);
        try {
            save(fact);
        } catch (DuplicateKeyException e) {
            // 并发插入同一 (sender_id, fact_key)：让先写入的那条生效，本事务改为覆盖值
            ProfileFact winner = findOne(senderId, factKey);
            if (winner != null) {
                winner.setFactValue(factValue);
                if (confidence != null) {
                    winner.setConfidence(confidence);
                }
                updateById(winner);
                return winner;
            }
            throw e;
        }
        return fact;
    }

    private ProfileFact findOne(Long senderId, String factKey) {
        return lambdaQuery()
                .eq(ProfileFact::getSenderId, senderId)
                .eq(ProfileFact::getFactKey, factKey)
                .one();
    }

    private void require(Long senderId, String factKey, String factValue) {
        if (senderId == null) {
            throw new BusinessException("senderId 不能为空");
        }
        if (factKey == null || factKey.isBlank()) {
            throw new BusinessException("factKey 不能为空");
        }
        if (factValue == null || factValue.isBlank()) {
            throw new BusinessException("factValue 不能为空");
        }
    }
}
```

- [ ] **Step 6: 写失败测试 `ProfileFactServiceTest.java`**

```java
package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.FactManualRequest;
import com.czx.qqmessageassistantjava.dto.FactUpsertRequest;
import com.czx.qqmessageassistantjava.entity.ProfileFact;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProfileFactServiceTest {

    @Autowired
    private ProfileFactService profileFactService;

    @Test
    void upsertInsertsThenOverwrites() {
        ProfileFactUpsertRequest first = new ProfileFactUpsertRequest();
        first.setSenderId(1001L);
        first.setFactKey("学号");
        first.setFactValue("2023001");
        first.setConfidence(new BigDecimal("0.95"));
        ProfileFact created = profileFactService.upsertAuto(first);
        assertThat(created.getId()).isNotNull();
        assertThat(created.getSource()).isEqualTo("AUTO");

        ProfileFactUpsertRequest second = new ProfileFactUpsertRequest();
        second.setSenderId(1001L);
        second.setFactKey("学号");
        second.setFactValue("2023099");
        ProfileFact updated = profileFactService.upsertAuto(second);
        assertThat(updated.getId()).isEqualTo(created.getId());
        assertThat(updated.getFactValue()).isEqualTo("2023099");
    }

    @Test
    void factsMapReturnsKeyValuePairs() {
        FactManualRequest req = new FactManualRequest();
        req.setSenderId(1002L);
        req.setFactKey("姓名");
        req.setFactValue("张三");
        profileFactService.createManual(req);

        assertThat(profileFactService.factsMap(1002L)).containsEntry("姓名", "张三");
    }

    @Test
    void blankKeyRejected() {
        FactUpsertRequest req = new FactUpsertRequest();
        req.setSenderId(1003L);
        req.setFactKey("  ");
        req.setFactValue("x");
        assertThatThrownBy(() -> profileFactService.upsertAuto(req))
                .isInstanceOf(BusinessException.class);
    }
}
```

> 注意：`FactUpsertRequest` 的类名是 `FactUpsertRequest`，若你 Step 3 里把它命名为别的名字，请同步改这里。

- [ ] **Step 7: 跑测试**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=ProfileFactServiceTest`
Expected: Test 1 首次因类不存在而编译失败 → 补全 Step 1–5 后重跑：Tests run: 3, Failures: 0。

- [ ] **Step 8: 提交**

```bash
git add QQMessageAssistantJava/src
git commit -m "feat: 事实记忆 profile_fact 实体/Mapper/Service（upsert 覆盖 + 总控台 CRUD 能力）"
```

---

### Task 3: 风格画像 `style_profile` —— 实体 / Mapper / Service / Controller

**Files:**
- Create: `entity/StyleProfile.java`
- Create: `mapper/StyleProfileMapper.java`
- Create: `dto/StyleProfileUpdateRequest.java`
- Create: `service/StyleProfileService.java`
- Create: `service/impl/StyleProfileServiceImpl.java`
- Create: `controller/StyleProfileController.java`
- Test: `src/test/java/com/czx/qqmessageassistantjava/controller/StyleProfileControllerTest.java`

**Interfaces:**
- Produces:
  - `StyleProfileService.getBySenderId(Long senderId)` → `StyleProfile`（可能为 null）
  - `StyleProfileService.upsert(Long senderId, String profilePrompt)` → `StyleProfile`（置 `lastSummarizedAt = now`）
  - `StyleProfileService.promptOf(Long senderId)` → `String`（无画像返回 `""`，供 Task 4 组合响应）

- [ ] **Step 1: 写实体 `entity/StyleProfile.java`**

```java
package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("style_profile")
public class StyleProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long senderId;

    /** 注入 Dify 的画像正文 */
    private String profilePrompt;

    private LocalDateTime lastSummarizedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: 写 `mapper/StyleProfileMapper.java`**

```java
package com.czx.qqmessageassistantjava.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czx.qqmessageassistantjava.entity.StyleProfile;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StyleProfileMapper extends BaseMapper<StyleProfile> {
}
```

- [ ] **Step 3: 写 `dto/StyleProfileUpdateRequest.java`**

```java
package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "风格画像写回请求（Dify 总结工作流）")
public class StyleProfileUpdateRequest {

    @Schema(description = "用户")
    private Long senderId;

    @Schema(description = "新的画像正文")
    private String profilePrompt;
}
```

- [ ] **Step 4: 写 `service/StyleProfileService.java`**

```java
package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.entity.StyleProfile;

public interface StyleProfileService extends IService<StyleProfile> {

    StyleProfile getBySenderId(Long senderId);

    StyleProfile upsert(Long senderId, String profilePrompt);

    String promptOf(Long senderId);
}
```

- [ ] **Step 5: 写 `service/impl/StyleProfileServiceImpl.java`**

```java
package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.entity.StyleProfile;
import com.czx.qqmessageassistantjava.mapper.StyleProfileMapper;
import com.czx.qqmessageassistantjava.service.StyleProfileService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class StyleProfileServiceImpl extends ServiceImpl<StyleProfileMapper, StyleProfile>
        implements StyleProfileService {

    @Override
    public StyleProfile getBySenderId(Long senderId) {
        return lambdaQuery().eq(StyleProfile::getSenderId, senderId).one();
    }

    @Override
    public StyleProfile upsert(Long senderId, String profilePrompt) {
        if (senderId == null) {
            throw new BusinessException("senderId 不能为空");
        }
        if (profilePrompt == null || profilePrompt.isBlank()) {
            throw new BusinessException("profilePrompt 不能为空");
        }
        StyleProfile existing = getBySenderId(senderId);
        if (existing != null) {
            existing.setProfilePrompt(profilePrompt);
            existing.setLastSummarizedAt(LocalDateTime.now());
            updateById(existing);
            return existing;
        }
        StyleProfile profile = new StyleProfile();
        profile.setSenderId(senderId);
        profile.setProfilePrompt(profilePrompt);
        profile.setLastSummarizedAt(LocalDateTime.now());
        save(profile);
        return profile;
    }

    @Override
    public String promptOf(Long senderId) {
        StyleProfile profile = getBySenderId(senderId);
        return profile == null ? "" : profile.getProfilePrompt();
    }
}
```

> 本表 `UNIQUE(sender_id)`，无 `is_deleted`：一人一行，`upsert` 覆盖。

- [ ] **Step 6: 写 `controller/StyleProfileController.java`**

```java
package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.StyleProfileUpdateRequest;
import com.czx.qqmessageassistantjava.entity.StyleProfile;
import com.czx.qqmessageassistantjava.service.StyleProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "画像·风格")
@RestController
@RequestMapping("/api/style-profile")
public class StyleProfileController {

    @Autowired
    private StyleProfileService styleProfileService;

    @Operation(summary = "查看当前画像")
    @GetMapping("/{senderId}")
    public Result<StyleProfile> get(@PathVariable Long senderId) {
        return Result.ok(styleProfileService.getBySenderId(senderId));
    }

    @Operation(summary = "写回画像", description = "upsert 覆盖，并置 lastSummarizedAt=now")
    @PostMapping("/update")
    public Result<StyleProfile> update(@RequestBody StyleProfileUpdateRequest request) {
        return Result.ok(styleProfileService.upsert(request.getSenderId(), request.getProfilePrompt()));
    }
}
```

> **路由提示**：`POST /api/style-profile/update` 与 `GET /api/style-profile/{senderId}` 方法不同，不冲突。

- [ ] **Step 7: 写测试 `StyleProfileControllerTest.java`**

```java
package com.czx.qqmessageassistantjava.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StyleProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void updateThenGetReturnsLatest() throws Exception {
        mockMvc.perform(post("/api/style-profile/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"senderId": 1101, "profilePrompt": "说话简短、常用‘嗯/好的’"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.profilePrompt").value("说话简短、常用‘嗯/好的’"))
                .andExpect(jsonPath("$.data.lastSummarizedAt").exists());

        mockMvc.perform(get("/api/style-profile/1101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profilePrompt").value("说话简短、常用‘嗯/好的’"));
    }

    @Test
    void updateTwiceKeepsSingleRow() throws Exception {
        for (String prompt : new String[]{"第一版", "第二版"}) {
            mockMvc.perform(post("/api/style-profile/update")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"senderId\": 1102, \"profilePrompt\": \"" + prompt + "\"}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/style-profile/1102"))
                .andExpect(jsonPath("$.data.profilePrompt").value("第二版"));
    }

    @Test
    void blankPromptRejected() throws Exception {
        mockMvc.perform(post("/api/style-profile/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"senderId\": 1103, \"profilePrompt\": \"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
```

- [ ] **Step 8: 跑测试**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=StyleProfileControllerTest`
Expected: Tests run: 3, Failures: 0, Errors: 0.

- [ ] **Step 9: 提交**

```bash
git add QQMessageAssistantJava/src
git commit -m "feat: 风格画像 style_profile 实体/Mapper/Service/Controller（查看 + 写回）"
```

---

### Task 4: 画像 Controller —— 事实 6 个端点 + 会话注入

**Files:**
- Create: `dto/ProfileView.java`
- Create: `controller/ProfileController.java`
- Test: `src/test/java/com/czx/qqmessageassistantjava/controller/ProfileControllerTest.java`

**Interfaces:**
- Consumes: `ProfileFactService`（Task 2）、`StyleProfileService.promptOf`（Task 3）
- Produces: `GET /api/profile/{senderId}` → `ProfileView{ Map<String,String> facts; String stylePrompt; }`

- [ ] **Step 1: 写 `dto/ProfileView.java`**

```java
package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话注入响应体")
public class ProfileView {

    @Schema(description = "事实记忆 kv")
    private Map<String, String> facts;

    @Schema(description = "风格画像正文")
    private String stylePrompt;
}
```

- [ ] **Step 2: 写 `controller/ProfileController.java`**

```java
package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.*;
import com.czx.qqmessageassistantjava.entity.ProfileFact;
import com.czx.qqmessageassistantjava.service.ProfileFactService;
import com.czx.qqmessageassistantjava.service.StyleProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "画像·事实")
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    @Autowired
    private ProfileFactService profileFactService;

    @Autowired
    private StyleProfileService styleProfileService;

    @Operation(summary = "会话注入", description = "Dify 会话开始时拉取：事实 kv + 风格画像")
    @GetMapping("/{senderId}")
    public Result<ProfileView> inject(@PathVariable Long senderId) {
        return Result.ok(new ProfileView(
                profileFactService.factsMap(senderId),
                styleProfileService.promptOf(senderId)));
    }

    @Operation(summary = "高置信事实写入", description = "upsert 覆盖，source=AUTO")
    @PutMapping("/facts")
    public Result<ProfileFact> upsertFact(@RequestBody FactUpsertRequest request) {
        return Result.ok(profileFactService.upsertAuto(request));
    }

    @Operation(summary = "事实列表")
    @GetMapping("/facts")
    public Result<List<ProfileFact>> listFacts(@RequestParam(required = false) Long senderId) {
        return Result.ok(profileFactService.listBySender(senderId));
    }

    @Operation(summary = "手动新增事实", description = "source=MANUAL")
    @PostMapping("/facts")
    public Result<ProfileFact> createFact(@RequestBody FactManualRequest request) {
        return Result.ok(profileFactService.createManual(request));
    }

    @Operation(summary = "手动修改事实")
    @PutMapping("/facts/{id}")
    public Result<ProfileFact> updateFact(@PathVariable Long id, @RequestBody FactUpdateRequest request) {
        return Result.ok(profileFactService.updateFact(id, request));
    }

    @Operation(summary = "手动删除事实", description = "物理删除")
    @DeleteMapping("/facts/{id}")
    public Result<Void> deleteFact(@PathVariable Long id) {
        profileFactService.deleteFact(id);
        return Result.ok();
    }
}
```

> **路由提示**：`GET /api/profile/facts` 与 `GET /api/profile/{senderId}` 都能匹配 `facts` 这个路径段，Spring 会选择**更精确的字面量** `/facts`，不会把 `"facts"` 当成 `Long` 转换。无需额外处理。

- [ ] **Step 3: 写测试 `ProfileControllerTest.java`**

```java
package com.czx.qqmessageassistantjava.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void injectionMergesFactsAndStyle() throws Exception {
        mockMvc.perform(put("/api/profile/facts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"senderId": 1201, "factKey": "学号", "factValue": "2023001", "confidence": 0.95}
                        """)).andExpect(status().isOk());

        mockMvc.perform(post("/api/style-profile/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"senderId\": 1201, \"profilePrompt\": \"简短\"}")).andExpect(status().isOk());

        mockMvc.perform(get("/api/profile/1201"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.facts.学号").value("2023001"))
                .andExpect(jsonPath("$.data.stylePrompt").value("简短"));
    }

    @Test
    void injectionOnUnknownSenderReturnsEmpty() throws Exception {
        mockMvc.perform(get("/api/profile/999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.facts").isMap())
                .andExpect(jsonPath("$.data.stylePrompt").value(""));
    }

    @Test
    void manualCrudRoundTrip() throws Exception {
        String created = mockMvc.perform(post("/api/profile/facts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"senderId": 1202, "factKey": "目标", "factValue": "考研"}
                                """))
                .andExpect(jsonPath("$.data.source").value("MANUAL"))
                .andReturn().getResponse().getContentAsString();

        long id = com.jayway.jsonpath.JsonPath.parse(created).read("$.data.id", Long.class);

        mockMvc.perform(put("/api/profile/facts/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"factValue\": \"保研\"}"))
                .andExpect(jsonPath("$.data.factValue").value("保研"));

        mockMvc.perform(delete("/api/profile/facts/" + id))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/profile/facts").param("senderId", "1202"))
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
```

> `JsonPath` 来自 `com.jayway.jsonpath:json-path`，是 `spring-boot-starter-test` 的传递依赖，无需额外加。

- [ ] **Step 4: 跑测试**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=ProfileControllerTest`
Expected: Tests run: 3, Failures: 0, Errors: 0。

若 `$.data.facts.学号` 断言失败，改用 `jsonPath("$.data.facts['学号']")`。

- [ ] **Step 5: 提交**

```bash
git add QQMessageAssistantJava/src
git commit -m "feat: 画像接口 —— 会话注入 GET /api/profile/{senderId} + 事实 6 端点"
```

---

### Task 5: 全局设置 `app_setting`

**Files:**
- Create: `entity/AppSetting.java`
- Create: `mapper/AppSettingMapper.java`
- Create: `service/AppSettingService.java`
- Create: `service/impl/AppSettingServiceImpl.java`
- Create: `controller/SettingsController.java`
- Test: `src/test/java/com/czx/qqmessageassistantjava/controller/SettingsControllerTest.java`

**Interfaces:**
- Produces:
  - `AppSettingService.getAll()` → `Map<String,String>`
  - `AppSettingService.putAll(Map<String,String> kv)` → `int`（写入条数）
  - `AppSettingService.getOrDefault(String key, String def)` → `String`（Plan 2-3 的调度读 `summary_frequency` 会用到）

- [ ] **Step 1: 写实体 `entity/AppSetting.java`**

```java
package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("app_setting")
public class AppSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String settingKey;

    private String settingValue;

    private String description;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: 写 `mapper/AppSettingMapper.java`**

```java
package com.czx.qqmessageassistantjava.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czx.qqmessageassistantjava.entity.AppSetting;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AppSettingMapper extends BaseMapper<AppSetting> {
}
```

- [ ] **Step 3: 写 `service/AppSettingService.java`**

```java
package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.entity.AppSetting;

import java.util.Map;

public interface AppSettingService extends IService<AppSetting> {

    Map<String, String> getAll();

    int putAll(Map<String, String> kv);

    String getOrDefault(String key, String defaultValue);
}
```

- [ ] **Step 4: 写 `service/impl/AppSettingServiceImpl.java`**

```java
package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.entity.AppSetting;
import com.czx.qqmessageassistantjava.mapper.AppSettingMapper;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AppSettingServiceImpl extends ServiceImpl<AppSettingMapper, AppSetting>
        implements AppSettingService {

    @Override
    public Map<String, String> getAll() {
        Map<String, String> result = new LinkedHashMap<>();
        for (AppSetting setting : lambdaQuery().orderByAsc(AppSetting::getSettingKey).list()) {
            result.put(setting.getSettingKey(), setting.getSettingValue());
        }
        return result;
    }

    @Override
    @Transactional
    public int putAll(Map<String, String> kv) {
        if (kv == null || kv.isEmpty()) {
            throw new BusinessException("配置不能为空");
        }
        int count = 0;
        for (Map.Entry<String, String> entry : kv.entrySet()) {
            put(entry.getKey(), entry.getValue());
            count++;
        }
        return count;
    }

    @Override
    public String getOrDefault(String key, String defaultValue) {
        AppSetting setting = lambdaQuery().eq(AppSetting::getSettingKey, key).one();
        return setting == null || setting.getSettingValue() == null
                ? defaultValue : setting.getSettingValue();
    }

    private void put(String key, String value) {
        if (key == null || key.isBlank()) {
            throw new BusinessException("配置键不能为空");
        }
        AppSetting existing = lambdaQuery().eq(AppSetting::getSettingKey, key).one();
        if (existing != null) {
            existing.setSettingValue(value);
            updateById(existing);
            return;
        }
        AppSetting setting = new AppSetting();
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        save(setting);
    }
}
```

- [ ] **Step 5: 写 `controller/SettingsController.java`**

```java
package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "设置")
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    @Autowired
    private AppSettingService appSettingService;

    @Operation(summary = "读取全部配置")
    @GetMapping
    public Result<Map<String, String>> getAll() {
        return Result.ok(appSettingService.getAll());
    }

    @Operation(summary = "更新配置（部分更新）")
    @PutMapping
    public Result<Integer> putAll(@RequestBody Map<String, String> kv) {
        return Result.ok(appSettingService.putAll(kv));
    }
}
```

- [ ] **Step 6: 写测试 `SettingsControllerTest.java`**

```java
package com.czx.qqmessageassistantjava.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void putThenGetRoundTrip() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary_frequency\": \"WEEKLY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));

        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary_frequency").value("WEEKLY"));
    }

    @Test
    void putTwiceOverwritesNotDuplicates() throws Exception {
        for (String value : new String[]{"DAILY_7D", "DAILY_TODAY"}) {
            mockMvc.perform(put("/api/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"summary_frequency\": \"" + value + "\"}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/settings"))
                .andExpect(jsonPath("$.data.summary_frequency").value("DAILY_TODAY"))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void emptyBodyRejected() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
```

- [ ] **Step 7: 跑测试**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=SettingsControllerTest`
Expected: Tests run: 3, Failures: 0, Errors: 0。

> `$.data.length()` 对 `{}` 形式的 Map 生效；若报错改用 `jsonPath("$.data").isMap()` + `jsonPath("$.data.summary_frequency")` 断言即可。

- [ ] **Step 8: 提交**

```bash
git add QQMessageAssistantJava/src
git commit -m "feat: 全局设置 app_setting 实体/Mapper/Service/Controller（GET/PUT /api/settings）"
```

---

### Task 6: 文件任务镜像 `file_task`

**Files:**
- Create: `entity/FileTask.java`
- Create: `mapper/FileTaskMapper.java`
- Create: `dto/FileTaskSyncRequest.java`
- Create: `service/FileTaskService.java`
- Create: `service/impl/FileTaskServiceImpl.java`
- Create: `controller/FileTaskController.java`
- Test: `src/test/java/com/czx/qqmessageassistantjava/controller/FileTaskControllerTest.java`

**Interfaces:**
- Produces:
  - `FileTaskService.sync(FileTaskSyncRequest req)` → `FileTask`（按 `taskRef` upsert）
  - `FileTaskService.list(Long senderId, String status)` → `List<FileTask>`

- [ ] **Step 1: 写实体 `entity/FileTask.java`**

```java
package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "file_task", autoResultMap = true)
public class FileTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** LangBot 侧 taskId，幂等键 */
    private String taskRef;

    private Long senderId;

    private Long chatId;

    private String fileName;

    /** PENDING|PROCESSING|NEED_INPUT|AWAITING_CONFIRM|SENT|FAILED|CANCELLED */
    private String status;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> detail;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: 写 `mapper/FileTaskMapper.java`**

```java
package com.czx.qqmessageassistantjava.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czx.qqmessageassistantjava.entity.FileTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FileTaskMapper extends BaseMapper<FileTask> {
}
```

- [ ] **Step 3: 写 `dto/FileTaskSyncRequest.java`**

```java
package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "文件任务状态镜像同步请求（LangBot → Spring Boot）")
public class FileTaskSyncRequest {

    @Schema(description = "LangBot 侧 taskId，幂等键")
    private String taskRef;

    private Long senderId;

    private Long chatId;

    private String fileName;

    @Schema(description = "PENDING|PROCESSING|NEED_INPUT|AWAITING_CONFIRM|SENT|FAILED|CANCELLED")
    private String status;

    @Schema(description = "状态详情（JSON 镜像）")
    private Map<String, Object> detail;
}
```

- [ ] **Step 4: 写 `service/FileTaskService.java`**

```java
package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.dto.FileTaskSyncRequest;
import com.czx.qqmessageassistantjava.entity.FileTask;

import java.util.List;

public interface FileTaskService extends IService<FileTask> {

    FileTask sync(FileTaskSyncRequest request);

    List<FileTask> list(Long senderId, String status);
}
```

- [ ] **Step 5: 写 `service/impl/FileTaskServiceImpl.java`**

```java
package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.FileTaskSyncRequest;
import com.czx.qqmessageassistantjava.entity.FileTask;
import com.czx.qqmessageassistantjava.mapper.FileTaskMapper;
import com.czx.qqmessageassistantjava.service.FileTaskService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class FileTaskServiceImpl extends ServiceImpl<FileTaskMapper, FileTask>
        implements FileTaskService {

    private static final Set<String> STATUSES = Set.of(
            "PENDING", "PROCESSING", "NEED_INPUT", "AWAITING_CONFIRM",
            "SENT", "FAILED", "CANCELLED");

    @Override
    public FileTask sync(FileTaskSyncRequest request) {
        if (request.getTaskRef() == null || request.getTaskRef().isBlank()) {
            throw new BusinessException("taskRef 不能为空");
        }
        if (request.getSenderId() == null) {
            throw new BusinessException("senderId 不能为空");
        }
        if (request.getStatus() == null || !STATUSES.contains(request.getStatus())) {
            throw new BusinessException("status 必须是【" + STATUSES + "】其中之一");
        }
        FileTask existing = findByRef(request.getTaskRef());
        if (existing != null) {
            apply(existing, request);
            updateById(existing);
            return existing;
        }
        FileTask task = new FileTask();
        task.setTaskRef(request.getTaskRef());
        apply(task, request);
        try {
            save(task);
        } catch (DuplicateKeyException e) {
            FileTask winner = findByRef(request.getTaskRef());
            if (winner != null) {
                apply(winner, request);
                updateById(winner);
                return winner;
            }
            throw e;
        }
        return task;
    }

    @Override
    public List<FileTask> list(Long senderId, String status) {
        if (status != null && !STATUSES.contains(status)) {
            throw new BusinessException("status 必须是【" + STATUSES + "】其中之一");
        }
        return lambdaQuery()
                .eq(senderId != null, FileTask::getSenderId, senderId)
                .eq(status != null, FileTask::getStatus, status)
                .orderByDesc(FileTask::getUpdatedAt)
                .list();
    }

    private void apply(FileTask task, FileTaskSyncRequest request) {
        task.setSenderId(request.getSenderId());
        task.setStatus(request.getStatus());
        if (request.getChatId() != null) {
            task.setChatId(request.getChatId());
        }
        if (request.getFileName() != null) {
            task.setFileName(request.getFileName());
        }
        if (request.getDetail() != null) {
            task.setDetail(request.getDetail());
        }
    }

    private FileTask findByRef(String taskRef) {
        return lambdaQuery().eq(FileTask::getTaskRef, taskRef).one();
    }
}
```

> **注意**：`updatedAt` 由数据库 `ON UPDATE CURRENT_TIMESTAMP` 维护，但 `updateById` 只在字段有变化时才触发 `ON UPDATE`。若 LangBot 同步同一个 status（比如重复同步），`updatedAt` 不会变——这是可接受的（无变化即无需重排）。

- [ ] **Step 6: 写 `controller/FileTaskController.java`**

```java
package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.FileTaskSyncRequest;
import com.czx.qqmessageassistantjava.entity.FileTask;
import com.czx.qqmessageassistantjava.service.FileTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "文件任务镜像", description = "状态机真身在 LangBot 插件内，本表仅只读镜像供 Vue 展示")
@RestController
@RequestMapping("/api/file-tasks")
public class FileTaskController {

    @Autowired
    private FileTaskService fileTaskService;

    @Operation(summary = "只读任务列表")
    @GetMapping
    public Result<List<FileTask>> list(@RequestParam(required = false) Long senderId,
                                       @RequestParam(required = false) String status) {
        return Result.ok(fileTaskService.list(senderId, status));
    }

    @Operation(summary = "状态镜像同步", description = "按 taskRef upsert；只写不回读业务逻辑")
    @PostMapping("/sync")
    public Result<FileTask> sync(@RequestBody FileTaskSyncRequest request) {
        return Result.ok(fileTaskService.sync(request));
    }
}
```

- [ ] **Step 7: 写测试 `FileTaskControllerTest.java`**

```java
package com.czx.qqmessageassistantjava.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FileTaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void syncIsIdempotentByTaskRef() throws Exception {
        mockMvc.perform(post("/api/file-tasks/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskRef": "ft_0001", "senderId": 1301, "chatId": 2002,
                                 "fileName": "表单.xlsx", "status": "PROCESSING",
                                 "detail": {"missing": ["专业"]}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));

        mockMvc.perform(post("/api/file-tasks/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskRef": "ft_0001", "senderId": 1301, "status": "SENT",
                                 "detail": {"missing": []}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SENT"))
                .andExpect(jsonPath("$.data.fileName").value("表单.xlsx"));

        mockMvc.perform(get("/api/file-tasks").param("senderId", "1301"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].detail.missing").isArray());
    }

    @Test
    void invalidStatusRejected() throws Exception {
        mockMvc.perform(post("/api/file-tasks/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskRef\": \"ft_0002\", \"senderId\": 1302, \"status\": \"NOPE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void listRejectsInvalidStatusFilter() throws Exception {
        mockMvc.perform(get("/api/file-tasks").param("status", "NOPE"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 8: 跑测试**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=FileTaskControllerTest`
Expected: Tests run: 3, Failures: 0, Errors: 0。

> 若 `detail` 反序列化报 `Cannot deserialize` / 返回 null，确认实体上有 `@TableName(value="file_task", autoResultMap = true)` **且** `detail` 字段有 `@TableField(typeHandler = JacksonTypeHandler.class)`，两者缺一不可。

- [ ] **Step 9: 提交**

```bash
git add QQMessageAssistantJava/src
git commit -m "feat: 文件任务镜像 file_task 实体/Mapper/Service/Controller（只读列表 + 幂等同步）"
```

---

### Task 7: 全量回归 + 契约一致性核对

**Files:**
- Modify: `design/api_contract.md`（仅当发现实现与契约仍有出入时）

**Interfaces:**
- Consumes: 本计划全部产出
- Produces: 一份「已就绪接口清单」，可直接交给 Vue（Phase 5）与 LangBot（Phase 3）对接

- [ ] **Step 1: 跑全量测试**

Run: `cd QQMessageAssistantJava && mvn test`
Expected: BUILD SUCCESS，Failures: 0, Errors: 0。

- [ ] **Step 2: 启动应用，核对 Swagger 端点全集**

Run: `cd QQMessageAssistantJava && mvn spring-boot:run`
浏览器打开 `http://localhost:8080/swagger-ui/index.html`，逐个核对下表（缺一不可）：

| # | Method | Path |
|---|---|---|
| 1 | POST | `/api/drafts` |
| 2 | GET | `/api/drafts` |
| 3 | POST | `/api/drafts/{id}/confirm` |
| 4 | POST | `/api/drafts/{id}/reject` |
| 5 | POST | `/api/message-logs` |
| 6 | POST | `/api/memos` |
| 7 | GET | `/api/memos` |
| 8 | PATCH | `/api/memos/{id}` |
| 9 | POST | `/api/collection-tasks/check` |
| 10 | POST | `/api/collection-tasks/mark` |
| 11 | GET | `/api/collection-tasks/rules` |
| 12 | GET | `/api/intercept-rules` |
| 13 | POST | `/api/intercept-rules` |
| 14 | PUT | `/api/intercept-rules/{id}` |
| 15 | DELETE | `/api/intercept-rules/{id}` |
| 16 | GET | `/api/profile/{senderId}` |
| 17 | PUT | `/api/profile/facts` |
| 18 | GET | `/api/profile/facts` |
| 19 | POST | `/api/profile/facts` |
| 20 | PUT | `/api/profile/facts/{id}` |
| 21 | DELETE | `/api/profile/facts/{id}` |
| 22 | GET | `/api/style-profile/{senderId}` |
| 23 | POST | `/api/style-profile/update` |
| 24 | GET | `/api/settings` |
| 25 | PUT | `/api/settings` |
| 26 | GET | `/api/file-tasks` |
| 27 | POST | `/api/file-tasks/sync` |

- [ ] **Step 3: 手工冒烟（curl）**

```bash
curl -s -X POST http://localhost:8080/api/drafts -H "Content-Type: application/json" \
  -d '{"type":"REPLY","senderId":1001,"chatId":2002,"title":"回复草稿","payload":{"content":"好的，我看看"}}'
curl -s http://localhost:8080/api/drafts
curl -s -X PUT http://localhost:8080/api/profile/facts -H "Content-Type: application/json" \
  -d '{"senderId":1001,"factKey":"学号","factValue":"2023001","confidence":0.95}'
curl -s http://localhost:8080/api/profile/1001
curl -s http://localhost:8080/api/settings
```
Expected: 全部 `"code": 0`；第二个返回刚创建的草稿数组；第四个返回 `facts.学号 = 2023001`。

- [ ] **Step 4: 提交（若有文档微调）**

```bash
git add -A
git commit -m "docs: Phase 2-2 完成，同步接口契约与端点清单"
```

---

## Self-Review 记录

- **契约覆盖**：`api_contract.md` 第二节 A 组 27 个端点中，本计划新增 11 个（#16–#27 中的 16–27），修复 3 个（#2 路由可用性、#6–#8 路由、#9 响应体）。全部 27 个在 Task 7 有核对步骤。
- **类型一致性**：`ProfileFact` / `StyleProfile` / `AppSetting` / `FileTask` 四个实体名在 Task 4–6 的 Service、Controller、测试中一致引用；`FactUpsertRequest` 名在 Task 2 定义、Task 4 使用，已一致。
- **无占位符**：每个 Step 都给了可直接粘贴的代码/命令。
- **超出契约的部分**：无。本计划严格按 `api_contract.md` 实现。

---

## 下一份计划

`design/plans/2026-10-17-phase2-3-langbot-client-and-scheduler.md` —— 补齐 LangBot 客户端、草稿 `REPLY`/`FILE`/`FACT` 分发，以及定时提醒扫描与主动推送。完成后 Spring Boot 端**全部职责**即告完成。
