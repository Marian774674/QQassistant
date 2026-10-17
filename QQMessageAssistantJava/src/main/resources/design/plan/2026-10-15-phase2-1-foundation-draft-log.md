# Phase 2-1 实现计划：基础设施 + 统一草稿中心 + 统一消息日志

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Spring Boot 端具备 MySQL + MyBatis-Plus 持久化能力，并落地「统一草稿中心」(`draft`) 与「统一消息日志」(`message_log`) 两组 REST API。

**Architecture:** 分层 `Controller → Service(接口 + impl) → Mapper(MyBatis-Plus BaseMapper) → MySQL`。统一响应体 `ApiResponse<T>`；异常经 `@RestControllerAdvice` 统一处理。JSON 字段用 `JacksonTypeHandler`；逻辑删除由 MyBatis-Plus 全局配置驱动。本计划是 Phase 2 的地基——后续 Plan 2-2 / 2-3 直接复用此处的 `ApiResponse`、异常处理、数据源与 MyBatis-Plus 配置。

**Tech Stack:** Spring Boot 3.4.12 / Java 21 / MyBatis-Plus（`mybatis-plus-spring-boot3-starter`）/ MySQL 8 / Lombok / spring-boot-starter-validation。

## Global Constraints

- Java 21；Spring Boot 3.4.12（父 POM 已定，勿改）。
- 包根：`com.czx.qqmessageassistantjava`。
- 表结构以 `design/database_schema.md` 为唯一准据；本计划 Task 1 将其落成 `db/schema.sql`（**全部 10 张表**，供后续计划复用）。
- 主键：`@TableId(type = IdType.AUTO)`，对应 BIGINT 自增。
- 逻辑删除字段名：`is_deleted`；MyBatis-Plus 全局 `logic-delete-field: isDeleted`。**仅** `draft` / `memo` 使用（本计划只涉及 `draft`）。
- JSON 字段：实体类加 `@TableName(autoResultMap = true)`，字段加 `@TableField(typeHandler = JacksonTypeHandler.class)`。
- 统一响应体：`ApiResponse<T>{ int code; String message; T data; }`，**成功 `code = 0`**。
- 不建外键；跨表语义由应用层维护。
- 每个 Task 作为一个独立提交（`git commit`），提交信息见各 Task 末步。
- 时间类型统一用 `java.time.LocalDateTime`。

## 文件结构

```
QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/
├── QqMessageAssistantJavaApplication.java      (改：加 @MapperScan)
├── common/
│   ├── ApiResponse.java                        (新：统一响应体)
│   ├── BizException.java                       (新：业务异常)
│   └── GlobalExceptionHandler.java             (新：全局异常处理)
├── entity/
│   ├── Draft.java                              (新)
│   └── MessageLog.java                         (新)
├── mapper/
│   ├── DraftMapper.java                        (新)
│   └── MessageLogMapper.java                   (新)
├── dto/
│   ├── CreateDraftRequest.java                 (新)
│   └── MessageLogItem.java                     (新)
├── service/
│   ├── DraftService.java                       (新)
│   ├── MessageLogService.java                  (新)
│   └── impl/
│       ├── DraftServiceImpl.java               (新)
│       └── MessageLogServiceImpl.java          (新)
└── controller/
    ├── DraftController.java                    (新)
    └── MessageLogController.java               (新)

QQMessageAssistantJava/src/main/resources/
├── application.yaml                            (改：数据源 + mybatis-plus)
└── db/schema.sql                               (新：10 张表 DDL)

QQMessageAssistantJava/src/test/resources/
└── application-test.yaml                       (新：测试数据源 + 自动建表)

QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/
├── DatabaseConnectivityTest.java               (新)
├── common/ApiResponseTest.java                 (新)
├── mapper/DraftMapperTest.java                 (新)
└── controller/
    ├── DraftControllerTest.java                (新)
    └── MessageLogControllerTest.java           (新)
```

> 既有的 `controller/MessageController.java`（Dify 直连透传脚手架）**本计划不动**。

---

### Task 1: 接入 MySQL + MyBatis-Plus，落成 DDL

**Files:**
- Modify: `QQMessageAssistantJava/pom.xml`
- Create: `QQMessageAssistantJava/src/main/resources/db/schema.sql`
- Modify: `QQMessageAssistantJava/src/main/resources/application.yaml`
- Create: `QQMessageAssistantJava/src/test/resources/application-test.yaml`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/DatabaseConnectivityTest.java`

**Interfaces:**
- Produces: 可用的 `DataSource`、`JdbcTemplate` Bean；10 张表在 `qq_assistant` 与 `qq_assistant_test` 两个库中存在。

- [ ] **Step 1: 加依赖**

在 `pom.xml` 的 `<dependencies>` 内追加（`mysql-connector-j` 与 `lombok` 的版本由 Spring Boot 父 POM 管理，无需写 version）：

```xml
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
            <version>3.5.17</version>
        </dependency>

        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
```

- [ ] **Step 2: 验证依赖可解析**

Run: `cd QQMessageAssistantJava && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS（若 `3.5.17` 报无法解析，到 Maven Central 查 `mybatis-plus-spring-boot3-starter` 最新 3.5.x 版本号替换后重试）。

- [ ] **Step 3: 写 `db/schema.sql`**

将 `design/database_schema.md` 的 10 张表 DDL 落成此文件，`CREATE TABLE` 一律加 `IF NOT EXISTS`（测试库自动初始化需要幂等）：

```sql
-- 由 design/database_schema.md 落地；幂等，可重复执行。
CREATE TABLE IF NOT EXISTS `draft` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `type`         VARCHAR(16)     NOT NULL COMMENT 'REPLY|FILE|MEMO|FACT',
  `sender_id`    BIGINT UNSIGNED NOT NULL,
  `chat_id`      BIGINT UNSIGNED NULL,
  `title`        VARCHAR(255)    NULL COMMENT '列表展示用简短标题',
  `payload`      JSON            NOT NULL COMMENT '各 type 自定结构',
  `status`       VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|CONFIRMED|REJECTED',
  `confirmed_at` DATETIME        NULL,
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted`   TINYINT(1)      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_status_created` (`status`, `created_at`),
  KEY `idx_type_status` (`type`, `status`),
  KEY `idx_sender` (`sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一草稿中心';

CREATE TABLE IF NOT EXISTS `message_log` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id`  BIGINT UNSIGNED NOT NULL,
  `chat_id`    BIGINT UNSIGNED NULL,
  `direction`  VARCHAR(8)      NOT NULL COMMENT 'IN|OUT',
  `msg_type`   VARCHAR(16)     NOT NULL COMMENT 'text|image|file...',
  `content`    TEXT            NULL,
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_sender_created` (`sender_id`, `created_at`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一消息日志';

CREATE TABLE IF NOT EXISTS `memo` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id`     BIGINT UNSIGNED NOT NULL,
  `chat_id`       BIGINT UNSIGNED NULL,
  `title`         VARCHAR(255)    NOT NULL,
  `content`       TEXT            NULL,
  `importance`    TINYINT UNSIGNED NOT NULL COMMENT '1-5',
  `category`      VARCHAR(32)     NULL,
  `due_time`      DATETIME        NULL,
  `status`        VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
  `source_msg_id` BIGINT UNSIGNED NULL,
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted`    TINYINT(1)      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_sender_status` (`sender_id`, `status`),
  KEY `idx_status_due` (`status`, `due_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='备忘录';

CREATE TABLE IF NOT EXISTS `memo_reminder` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `memo_id`      BIGINT UNSIGNED NOT NULL,
  `offset_label` VARCHAR(16)     NOT NULL,
  `remind_at`    DATETIME        NOT NULL,
  `status`       VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
  `sent_at`      DATETIME        NULL,
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_remind` (`status`, `remind_at`),
  KEY `idx_memo` (`memo_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提醒计划';

CREATE TABLE IF NOT EXISTS `collection_task` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `chat_id`      BIGINT UNSIGNED NOT NULL,
  `task_type`    VARCHAR(32)     NOT NULL,
  `task_key`     VARCHAR(128)    NOT NULL,
  `window_start` DATETIME        NULL,
  `status`       VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
  `replied_at`   DATETIME        NULL,
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_task` (`chat_id`, `task_key`),
  KEY `idx_chat_status` (`chat_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一回复防重状态';

CREATE TABLE IF NOT EXISTS `intercept_rule` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`           VARCHAR(64)     NOT NULL,
  `keywords`       VARCHAR(512)    NULL,
  `regex`          VARCHAR(512)    NULL,
  `reply_template` TEXT            NULL,
  `priority`       INT             NOT NULL DEFAULT 0,
  `enabled`        TINYINT(1)      NOT NULL DEFAULT 1,
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_enabled_priority` (`enabled`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='前置拦截规则';

CREATE TABLE IF NOT EXISTS `profile_fact` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id`  BIGINT UNSIGNED NOT NULL,
  `fact_key`   VARCHAR(64)     NOT NULL,
  `fact_value` VARCHAR(512)    NOT NULL,
  `confidence` DECIMAL(3,2)    NULL,
  `source`     VARCHAR(16)     NOT NULL DEFAULT 'AUTO',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sender_key` (`sender_id`, `fact_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='事实记忆';

CREATE TABLE IF NOT EXISTS `style_profile` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id`          BIGINT UNSIGNED NOT NULL,
  `profile_prompt`     TEXT            NOT NULL,
  `last_summarized_at` DATETIME        NULL,
  `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sender` (`sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='风格画像';

CREATE TABLE IF NOT EXISTS `app_setting` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `setting_key`   VARCHAR(64)     NOT NULL,
  `setting_value` VARCHAR(512)    NULL,
  `description`   VARCHAR(255)    NULL,
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_setting_key` (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='全局配置';

CREATE TABLE IF NOT EXISTS `file_task` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_ref`   VARCHAR(64)     NOT NULL,
  `sender_id`  BIGINT UNSIGNED NOT NULL,
  `chat_id`    BIGINT UNSIGNED NULL,
  `file_name`  VARCHAR(255)    NULL,
  `status`     VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
  `detail`     JSON            NULL,
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_ref` (`task_ref`),
  KEY `idx_sender_status` (`sender_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件任务状态镜像';
```

- [ ] **Step 4: 改 `application.yaml`**

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/qq_assistant?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:CHANGE_ME}
    driver-class-name: com.mysql.cj.jdbc.Driver
  sql:
    init:
      mode: never

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    banner: false
    db-config:
      id-type: auto
      logic-delete-field: isDeleted
      logic-delete-value: 1
      logic-not-delete-value: 0
```

> `password` 默认值 `CHANGE_ME` 是占位——**执行者必须改成你本机 MySQL 的真实密码**（或用环境变量 `DB_PASSWORD` 传入）。

- [ ] **Step 5: 建库并导入 DDL**

Run（用你的 MySQL 账号）:
```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS qq_assistant DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE DATABASE IF NOT EXISTS qq_assistant_test DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
mysql -u root -p qq_assistant < QQMessageAssistantJava/src/main/resources/db/schema.sql
mysql -u root -p qq_assistant_test < QQMessageAssistantJava/src/main/resources/db/schema.sql
```
Expected: 无报错；`SHOW TABLES` 可见 10 张表。

- [ ] **Step 6: 写测试配置 `src/test/resources/application-test.yaml`**

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/qq_assistant_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
  sql:
    init:
      mode: always
      schema-locations: classpath:db/schema.sql
```

> `application.yaml` 在 test classpath 上同样会被加载，此处仅覆盖测试库地址与开启自动建表。

- [ ] **Step 7: 写失败测试**

`DatabaseConnectivityTest.java`：
```java
package com.czx.qqmessageassistantjava;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class DatabaseConnectivityTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void dataSourceIsReachable() {
        Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertThat(one).isEqualTo(1);
    }

    @Test
    void draftTableExists() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = DATABASE() AND table_name = 'draft'", Integer.class);
        assertThat(n).isEqualTo(1);
    }
}
```

- [ ] **Step 8: 运行测试**

Run: `cd QQMessageAssistantJava && mvn -q -Dtest=DatabaseConnectivityTest test`
Expected: 2 个测试 PASS。若连接失败，检查 Step 5 的库/表与 Step 4 的密码。

- [ ] **Step 9: 提交**

```bash
git add QQMessageAssistantJava/pom.xml QQMessageAssistantJava/src/main/resources QQMessageAssistantJava/src/test/resources QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/DatabaseConnectivityTest.java
git commit -m "feat(db): 接入 MySQL + MyBatis-Plus，落成 10 张表 DDL"
```

---

### Task 2: 统一响应体与全局异常处理

**Files:**
- Create: `common/ApiResponse.java`, `common/BizException.java`, `common/GlobalExceptionHandler.java`
- Test: `common/ApiResponseTest.java`

**Interfaces:**
- Produces:
  - `ApiResponse<T>`：`static <T> ApiResponse<T> ok(T data)`、`static <T> ApiResponse<T> ok()`、`static <T> ApiResponse<T> error(int code, String message)`；访问器 `code()` / `message()` / `data()`。
  - `BizException(int code, String)`、`BizException(String)`（默认 code=400）、`getCode()`。
  - `GlobalExceptionHandler`：`@RestControllerAdvice`，把 `BizException` → HTTP 400、参数校验失败 → HTTP 400、其他异常 → HTTP 500，响应体均为 `ApiResponse`。

- [ ] **Step 1: 写失败测试**

`common/ApiResponseTest.java`：
```java
package com.czx.qqmessageassistantjava.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void okWrapsDataWithCodeZero() {
        ApiResponse<String> r = ApiResponse.ok("x");
        assertThat(r.code()).isZero();
        assertThat(r.message()).isEqualTo("ok");
        assertThat(r.data()).isEqualTo("x");
    }

    @Test
    void okWithoutDataHasNullData() {
        ApiResponse<Void> r = ApiResponse.ok();
        assertThat(r.code()).isZero();
        assertThat(r.data()).isNull();
    }

    @Test
    void errorCarriesCodeAndMessage() {
        ApiResponse<Void> r = ApiResponse.error(400, "bad");
        assertThat(r.code()).isEqualTo(400);
        assertThat(r.message()).isEqualTo("bad");
        assertThat(r.data()).isNull();
    }

    @Test
    void bizExceptionDefaultsToCode400() {
        BizException e = new BizException("草稿不存在");
        assertThat(e.getCode()).isEqualTo(400);
        assertThat(e.getMessage()).isEqualTo("草稿不存在");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd QQMessageAssistantJava && mvn -q -Dtest=ApiResponseTest test`
Expected: 编译失败（`ApiResponse`/`BizException` 尚不存在）。

- [ ] **Step 3: 写实现**

`common/ApiResponse.java`：
```java
package com.czx.qqmessageassistantjava.common;

public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
```

`common/BizException.java`：
```java
package com.czx.qqmessageassistantjava.common;

public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        this(400, message);
    }

    public int getCode() {
        return code;
    }
}
```

`common/GlobalExceptionHandler.java`：
```java
package com.czx.qqmessageassistantjava.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(400, msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, e.getMessage()));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd QQMessageAssistantJava && mvn -q -Dtest=ApiResponseTest test`
Expected: 4 个测试 PASS。

- [ ] **Step 5: 提交**

```bash
git add QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/common
git add QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/common
git commit -m "feat(common): 统一响应体 ApiResponse 与全局异常处理"
```

---

### Task 3: Draft 实体 + Mapper

**Files:**
- Modify: `QqMessageAssistantJavaApplication.java`（加 `@MapperScan`）
- Create: `entity/Draft.java`, `mapper/DraftMapper.java`
- Test: `mapper/DraftMapperTest.java`

**Interfaces:**
- Consumes: Task 1 的数据源与 MyBatis-Plus 配置。
- Produces: `Draft` 实体（字段名与 `draft` 表列一一对应）；`DraftMapper extends BaseMapper<Draft>`，提供 `insert/selectById/updateById/deleteById/selectList`。

- [ ] **Step 1: 写失败测试**

`mapper/DraftMapperTest.java`：
```java
package com.czx.qqmessageassistantjava.mapper;

import com.czx.qqmessageassistantjava.entity.Draft;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DraftMapperTest {

    @Autowired
    private DraftMapper draftMapper;

    @Test
    void insertAppliesDbDefaultsAndRoundTripsJsonPayload() {
        Draft d = new Draft();
        d.setType("REPLY");
        d.setSenderId(1001L);
        d.setChatId(2002L);
        d.setTitle("t");
        d.setPayload(Map.of("content", "hello"));

        draftMapper.insert(d);

        assertThat(d.getId()).isNotNull();
        Draft got = draftMapper.selectById(d.getId());
        assertThat(got.getStatus()).isEqualTo("PENDING");       // DB 默认值
        assertThat(got.getPayload()).containsEntry("content", "hello");
        assertThat(got.getChatId()).isEqualTo(2002L);
    }

    @Test
    void deleteByIdIsLogical() {
        Draft d = new Draft();
        d.setType("REPLY");
        d.setSenderId(1001L);
        d.setPayload(Map.of("content", "hi"));
        draftMapper.insert(d);

        draftMapper.deleteById(d.getId());

        assertThat(draftMapper.selectById(d.getId())).isNull(); // 逻辑删除后查不到
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd QQMessageAssistantJava && mvn -q -Dtest=DraftMapperTest test`
Expected: 编译失败（`Draft`/`DraftMapper` 尚不存在）。

- [ ] **Step 3: 写实体与 Mapper**

`entity/Draft.java`：
```java
package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "draft", autoResultMap = true)
public class Draft {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String type;

    private Long senderId;

    private Long chatId;

    private String title;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    private String status;

    private LocalDateTime confirmedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer isDeleted;
}
```

`mapper/DraftMapper.java`：
```java
package com.czx.qqmessageassistantjava.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czx.qqmessageassistantjava.entity.Draft;

public interface DraftMapper extends BaseMapper<Draft> {
}
```

`QqMessageAssistantJavaApplication.java`（加 `@MapperScan`）：
```java
package com.czx.qqmessageassistantjava;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.czx.qqmessageassistantjava.mapper")
public class QqMessageAssistantJavaApplication {

    public static void main(String[] args) {
        SpringApplication.run(QqMessageAssistantJavaApplication.class, args);
    }

}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd QQMessageAssistantJava && mvn -q -Dtest=DraftMapperTest test`
Expected: 2 个测试 PASS。`deleteByIdIsLogical` 通过即证明逻辑删除全局配置生效。

- [ ] **Step 5: 提交**

```bash
git add QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/entity/Draft.java QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/mapper/DraftMapper.java QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/QqMessageAssistantJavaApplication.java QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/mapper/DraftMapperTest.java
git commit -m "feat(draft): Draft 实体与 Mapper"
```

---

### Task 4: 草稿创建接口 `POST /api/drafts`

**Files:**
- Create: `dto/CreateDraftRequest.java`, `service/DraftService.java`, `service/impl/DraftServiceImpl.java`, `controller/DraftController.java`
- Test: `controller/DraftControllerTest.java`

**Interfaces:**
- Consumes: `DraftMapper`（Task 3）、`ApiResponse`/`BizException`（Task 2）。
- Produces:
  - `DraftService.create(CreateDraftRequest) → Draft`（强制 `status=PENDING`）。
  - `POST /api/drafts` → `ApiResponse<Draft>`。

- [ ] **Step 1: 写失败测试**

`controller/DraftControllerTest.java`：
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DraftControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createDraftReturnsPendingDraft() throws Exception {
        String body = """
                {"type":"REPLY","senderId":1001,"chatId":2002,"title":"回复草稿",
                 "payload":{"content":"你好"}}
                """;

        mockMvc.perform(post("/api/drafts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.payload.content").value("你好"));
    }

    @Test
    void createDraftRejectsMissingType() throws Exception {
        String body = """
                {"senderId":1001,"payload":{"content":"x"}}
                """;

        mockMvc.perform(post("/api/drafts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd QQMessageAssistantJava && mvn -q -Dtest=DraftControllerTest test`
Expected: 失败——`/api/drafts` 返回 404（接口不存在）。

- [ ] **Step 3: 写 DTO / Service / Controller**

`dto/CreateDraftRequest.java`：
```java
package com.czx.qqmessageassistantjava.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record CreateDraftRequest(
        @NotBlank(message = "type 不能为空") String type,
        @NotNull(message = "senderId 不能为空") Long senderId,
        Long chatId,
        String title,
        @NotNull(message = "payload 不能为空") Map<String, Object> payload
) {
}
```

`service/DraftService.java`：
```java
package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.dto.CreateDraftRequest;
import com.czx.qqmessageassistantjava.entity.Draft;

public interface DraftService {

    Draft create(CreateDraftRequest req);
}
```

`service/impl/DraftServiceImpl.java`：
```java
package com.czx.qqmessageassistantjava.service.impl;

import com.czx.qqmessageassistantjava.dto.CreateDraftRequest;
import com.czx.qqmessageassistantjava.entity.Draft;
import com.czx.qqmessageassistantjava.mapper.DraftMapper;
import com.czx.qqmessageassistantjava.service.DraftService;
import org.springframework.stereotype.Service;

@Service
public class DraftServiceImpl implements DraftService {

    private final DraftMapper draftMapper;

    public DraftServiceImpl(DraftMapper draftMapper) {
        this.draftMapper = draftMapper;
    }

    @Override
    public Draft create(CreateDraftRequest req) {
        Draft d = new Draft();
        d.setType(req.type());
        d.setSenderId(req.senderId());
        d.setChatId(req.chatId());
        d.setTitle(req.title());
        d.setPayload(req.payload());
        d.setStatus("PENDING");
        draftMapper.insert(d);
        return draftMapper.selectById(d.getId());
    }
}
```

`controller/DraftController.java`：
```java
package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.ApiResponse;
import com.czx.qqmessageassistantjava.dto.CreateDraftRequest;
import com.czx.qqmessageassistantjava.entity.Draft;
import com.czx.qqmessageassistantjava.service.DraftService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/drafts")
public class DraftController {

    private final DraftService draftService;

    public DraftController(DraftService draftService) {
        this.draftService = draftService;
    }

    @PostMapping
    public ApiResponse<Draft> create(@RequestBody @Valid CreateDraftRequest req) {
        return ApiResponse.ok(draftService.create(req));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd QQMessageAssistantJava && mvn -q -Dtest=DraftControllerTest test`
Expected: 2 个测试 PASS。

- [ ] **Step 5: 提交**

```bash
git add QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/dto/CreateDraftRequest.java QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/controller/DraftController.java QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/controller/DraftControllerTest.java
git commit -m "feat(draft): 草稿创建接口 POST /api/drafts"
```

---

### Task 5: 草稿列表接口 `GET /api/drafts`

**Files:**
- Modify: `service/DraftService.java`, `service/impl/DraftServiceImpl.java`, `controller/DraftController.java`
- Test: `controller/DraftControllerTest.java`（追加用例）

**Interfaces:**
- Produces: `DraftService.list(String status, String type) → List<Draft>`；`GET /api/drafts?status=&type=` → `ApiResponse<List<Draft>>`（按 `created_at` 倒序）。

- [ ] **Step 1: 追加失败测试**

在 `DraftControllerTest` 内新增用例：
```java
    @Test
    void listReturnsOnlyMatchingStatusNewestFirst() throws Exception {
        String pending = """
                {"type":"REPLY","senderId":1001,"payload":{"content":"p1"}}
                """;
        String other = """
                {"type":"MEMO","senderId":1001,"payload":{"content":"m1"}}
                """;
        mockMvc.perform(post("/api/drafts")
                .contentType(MediaType.APPLICATION_JSON).content(pending));
        mockMvc.perform(post("/api/drafts")
                .contentType(MediaType.APPLICATION_JSON).content(other));

        mockMvc.perform(get("/api/drafts")
                        .param("status", "PENDING").param("type", "REPLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].type").value("REPLY"));
    }
```
并在文件头补 import：
```java
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
```

- [ ] **Step 2: 运行确认失败**

Run: `cd QQMessageAssistantJava && mvn -q -Dtest=DraftControllerTest#listReturnsOnlyMatchingStatusNewestFirst test`
Expected: FAIL（404）。

- [ ] **Step 3: 实现**

`service/DraftService.java` 追加方法：
```java
    java.util.List<Draft> list(String status, String type);
```

`service/impl/DraftServiceImpl.java` 追加 import 与方法：
```java
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.List;
```
```java
    @Override
    public List<Draft> list(String status, String type) {
        QueryWrapper<Draft> qw = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq("status", status);
        }
        if (type != null && !type.isBlank()) {
            qw.eq("type", type);
        }
        qw.orderByDesc("created_at");
        return draftMapper.selectList(qw);
    }
```

`controller/DraftController.java` 追加 import 与方法：
```java
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;
```
```java
    @GetMapping
    public ApiResponse<List<Draft>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type) {
        return ApiResponse.ok(draftService.list(status, type));
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `cd QQMessageAssistantJava && mvn -q -Dtest=DraftControllerTest test`
Expected: 3 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/controller/DraftController.java QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/controller/DraftControllerTest.java
git commit -m "feat(draft): 草稿列表接口 GET /api/drafts"
```

---

### Task 6: 草稿确认 / 拒绝接口

**Files:**
- Modify: `service/DraftService.java`, `service/impl/DraftServiceImpl.java`, `controller/DraftController.java`
- Test: `controller/DraftControllerTest.java`（追加用例）

**Interfaces:**
- Produces:
  - `DraftService.confirm(Long id) → Draft`：`PENDING → CONFIRMED`，置 `confirmed_at = now`；非 `PENDING` 抛 `BizException`；不存在抛 `BizException`。
  - `DraftService.reject(Long id) → Draft`：`PENDING → REJECTED`。
  - `POST /api/drafts/{id}/confirm`、`POST /api/drafts/{id}/reject` → `ApiResponse<Draft>`。

> **范围说明**：`confirm` 现在只做状态流转。`REPLY/FILE → 通知 LangBot`、`MEMO/FACT → 写业务表` 的分发动作依赖尚未实现的下游（LangBot 接口 / `memo` / `profile_fact`），在 Plan 2-2、2-3 中接入。这是显式的分期，不是占位实现。

- [ ] **Step 1: 追加失败测试**
```java
    @Test
    void confirmMovesPendingToConfirmed() throws Exception {
        String body = """
                {"type":"REPLY","senderId":1001,"payload":{"content":"x"}}
                """;
        String created = mockMvc.perform(post("/api/drafts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        Long id = com.jayway.jsonpath.JsonPath.read(created, "$.data.id", Long.class);

        mockMvc.perform(post("/api/drafts/" + id + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmedAt").isNotEmpty());
    }

    @Test
    void confirmTwiceIsRejected() throws Exception {
        String body = """
                {"type":"REPLY","senderId":1001,"payload":{"content":"x"}}
                """;
        String created = mockMvc.perform(post("/api/drafts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        Long id = com.jayway.jsonpath.JsonPath.read(created, "$.data.id", Long.class);

        mockMvc.perform(post("/api/drafts/" + id + "/confirm"));
        mockMvc.perform(post("/api/drafts/" + id + "/confirm"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void confirmMissingDraftReturns400() throws Exception {
        mockMvc.perform(post("/api/drafts/99999999/confirm"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `cd QQMessageAssistantJava && mvn -q -Dtest=DraftControllerTest test`
Expected: 新增 3 个用例 FAIL（404）。

- [ ] **Step 3: 实现**

`service/DraftService.java` 追加：
```java
    Draft confirm(Long id);

    Draft reject(Long id);
```

`service/impl/DraftServiceImpl.java` 追加 import 与方法：
```java
import com.czx.qqmessageassistantjava.common.BizException;
import java.time.LocalDateTime;
```
```java
    @Override
    public Draft confirm(Long id) {
        return transition(id, "CONFIRMED");
    }

    @Override
    public Draft reject(Long id) {
        return transition(id, "REJECTED");
    }

    private Draft transition(Long id, String target) {
        Draft d = draftMapper.selectById(id);
        if (d == null) {
            throw new BizException("草稿不存在: " + id);
        }
        if (!"PENDING".equals(d.getStatus())) {
            throw new BizException("草稿当前状态为 " + d.getStatus() + "，不可再处理");
        }
        d.setStatus(target);
        if ("CONFIRMED".equals(target)) {
            d.setConfirmedAt(LocalDateTime.now());
        }
        draftMapper.updateById(d);
        return draftMapper.selectById(id);
    }
```

`controller/DraftController.java` 追加 import 与方法：
```java
import org.springframework.web.bind.annotation.PathVariable;
```
```java
    @PostMapping("/{id}/confirm")
    public ApiResponse<Draft> confirm(@PathVariable Long id) {
        return ApiResponse.ok(draftService.confirm(id));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Draft> reject(@PathVariable Long id) {
        return ApiResponse.ok(draftService.reject(id));
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `cd QQMessageAssistantJava && mvn -q -Dtest=DraftControllerTest test`
Expected: 6 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/controller/DraftController.java QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/controller/DraftControllerTest.java
git commit -m "feat(draft): 草稿确认/拒绝接口（状态流转）"
```

---

### Task 7: 消息日志实体 + 批量写入接口 `POST /api/message-logs`

**Files:**
- Create: `entity/MessageLog.java`, `mapper/MessageLogMapper.java`, `dto/MessageLogItem.java`, `service/MessageLogService.java`, `service/impl/MessageLogServiceImpl.java`, `controller/MessageLogController.java`
- Test: `controller/MessageLogControllerTest.java`

**Interfaces:**
- Consumes: `ApiResponse`（Task 2）、`@MapperScan`（Task 3）。
- Produces:
  - `MessageLogMapper extends BaseMapper<MessageLog>`。
  - `MessageLogService.saveBatch(List<MessageLogItem>) → int`（返回写入条数）。
  - `POST /api/message-logs`，请求体为 `MessageLogItem` 数组 → `ApiResponse<Integer>`。

- [ ] **Step 1: 写失败测试**

`controller/MessageLogControllerTest.java`：
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
class MessageLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void saveBatchPersistsAllItems() throws Exception {
        String body = """
                [
                  {"senderId":1001,"chatId":2002,"direction":"IN","msgType":"text","content":"在吗"},
                  {"senderId":1001,"chatId":2002,"direction":"OUT","msgType":"text","content":"在"}
                ]
                """;

        mockMvc.perform(post("/api/message-logs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(2));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd QQMessageAssistantJava && mvn -q -Dtest=MessageLogControllerTest test`
Expected: FAIL（编译失败，类不存在）。

- [ ] **Step 3: 写实现**

`entity/MessageLog.java`：
```java
package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("message_log")
public class MessageLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long senderId;

    private Long chatId;

    private String direction;

    private String msgType;

    private String content;

    private LocalDateTime createdAt;
}
```

`mapper/MessageLogMapper.java`：
```java
package com.czx.qqmessageassistantjava.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czx.qqmessageassistantjava.entity.MessageLog;

public interface MessageLogMapper extends BaseMapper<MessageLog> {
}
```

`dto/MessageLogItem.java`：
```java
package com.czx.qqmessageassistantjava.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record MessageLogItem(
        @NotNull(message = "senderId 不能为空") Long senderId,
        Long chatId,
        @NotBlank(message = "direction 不能为空") String direction,
        @NotBlank(message = "msgType 不能为空") String msgType,
        String content
) {
}
```

`service/MessageLogService.java`：
```java
package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.dto.MessageLogItem;

import java.util.List;

public interface MessageLogService {

    int saveBatch(List<MessageLogItem> items);
}
```

`service/impl/MessageLogServiceImpl.java`：
```java
package com.czx.qqmessageassistantjava.service.impl;

import com.czx.qqmessageassistantjava.dto.MessageLogItem;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.MessageLogService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MessageLogServiceImpl implements MessageLogService {

    private final MessageLogMapper messageLogMapper;

    public MessageLogServiceImpl(MessageLogMapper messageLogMapper) {
        this.messageLogMapper = messageLogMapper;
    }

    @Override
    public int saveBatch(List<MessageLogItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        int n = 0;
        for (MessageLogItem it : items) {
            MessageLog log = new MessageLog();
            log.setSenderId(it.senderId());
            log.setChatId(it.chatId());
            log.setDirection(it.direction());
            log.setMsgType(it.msgType());
            log.setContent(it.content());
            log.setCreatedAt(now);
            n += messageLogMapper.insert(log);
        }
        return n;
    }
}
```

`controller/MessageLogController.java`：
```java
package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.ApiResponse;
import com.czx.qqmessageassistantjava.dto.MessageLogItem;
import com.czx.qqmessageassistantjava.service.MessageLogService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/message-logs")
public class MessageLogController {

    private final MessageLogService messageLogService;

    public MessageLogController(MessageLogService messageLogService) {
        this.messageLogService = messageLogService;
    }

    @PostMapping
    public ApiResponse<Integer> saveBatch(@RequestBody @Valid List<MessageLogItem> items) {
        return ApiResponse.ok(messageLogService.saveBatch(items));
    }
}
```

> `@Valid` 作用于 `List<@Valid ...>` 元素需 Bean Validation 2.0+，Spring Boot 3 已支持（元素级校验由 Hibernate Validator 自动应用）。

- [ ] **Step 4: 运行确认通过**

Run: `cd QQMessageAssistantJava && mvn -q -Dtest=MessageLogControllerTest test`
Expected: 1 个测试 PASS。

- [ ] **Step 5: 提交**

```bash
git add QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/entity/MessageLog.java QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/mapper/MessageLogMapper.java QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/dto/MessageLogItem.java QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/MessageLogService.java QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/impl/MessageLogServiceImpl.java QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/controller/MessageLogController.java QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/controller/MessageLogControllerTest.java
git commit -m "feat(log): 消息日志批量写入接口 POST /api/message-logs"
```

---

### Task 8: 全量回归

- [ ] **Step 1: 跑全部测试**

Run: `cd QQMessageAssistantJava && mvn -q test`
Expected: BUILD SUCCESS，所有测试通过（`DatabaseConnectivityTest` / `ApiResponseTest` / `DraftMapperTest` / `DraftControllerTest` / `MessageLogControllerTest`，以及既有的 `QqMessageAssistantJavaApplicationTests`）。

- [ ] **Step 2: 启动应用冒烟**

Run: `cd QQMessageAssistantJava && mvn -q spring-boot:run`
Expected: 应用在 8080 启动无异常；`Ctrl+C` 退出。

- [ ] **Step 3: 提交（若 Step 1/2 有零散改动）**

```bash
git add -A
git commit -m "chore(phase2-1): 全量回归"
```

---

## Self-Review 记录

**Spec 覆盖**（对照 `design/database_schema.md` 与 `optimization_schemes.md` 的 API 契约）：
- `POST /api/drafts`、`GET /api/drafts?status=`、`POST /api/drafts/{id}/confirm|reject` → Task 4/5/6 ✅（对应抽象① 契约）。
- `POST /api/message-logs` → Task 7 ✅（对应抽象② 契约）。
- 10 张表 DDL → Task 1 ✅（其余表的实体/接口留给 Plan 2-2 / 2-3）。
- 未覆盖且**故意延后**：`confirm` 的类型分发下游（Plan 2-2/2-3）、`memo`/`collection_task`/`intercept_rule`/`profile_fact`/`style_profile`/`app_setting`/`file_task` 的接口（Plan 2-2 / 2-3）。

**占位符扫描**：无 TBD/TODO；`application.yaml` 的 `password: CHANGE_ME` 是显式的待填配置（Step 4 已标注执行者必须修改），非实现占位。

**类型一致性**：`ApiResponse.ok/error` 签名（Task 2）在 Task 4–7 的 Controller 中一致使用；`DraftMapper`/`MessageLogMapper` 均继承 `BaseMapper`；`DraftService` 方法名 `create/list/confirm/reject`（Task 4/5/6）前后一致；`MessageLogService.saveBatch` 命名一致。

**已知风险**：`mybatis-plus-spring-boot3-starter:3.5.17` 版本号需以 Maven Central 为准（Task 1 Step 2 有验证步骤）；测试依赖本机 MySQL 与两个已建库（Task 1 Step 5）。
