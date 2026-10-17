# Phase 2-3 实现计划：LangBot 客户端 + 草稿分发 + 定时提醒推送

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐 Spring Boot 端最后三块职责 —— ①有了能调 LangBot 的 HTTP 客户端；②草稿确认后 `REPLY` / `FILE` / `FACT` 三种分发真正落地；③`memo_reminder` 到期被扫到并通过 LangBot 推送。**完成后 Spring Boot 端全部职责完成**，可整体交给 Phase 3（LangBot 插件）对接。

**Architecture:** 新增一层出向客户端 `client/LangBotClient`（Spring `RestClient`，**只负责 HTTP 序列化，不含业务判断**）；`DraftServiceImpl.dispatch` 由空壳改为真实分发；新增 `schedule/MemoReminderScheduler` 由 `@Scheduled` 驱动，扫到期提醒 → 组装文案 → 调 `LangBotClient` → 回写状态。

**Tech Stack:** Spring Boot 3.4.12 / Java 21 / MyBatis-Plus 3.5.9 / MySQL 8 / Spring `RestClient`（Boot 3.4 自带，无需新依赖）/ Lombok / hutool。

## Global Constraints

- 延续 Plan 2-2 的全部约定：`com.czx.qqmessageassistantjava` 包根、`common.Result<T>`、`common.BusinessException`、`@TableId(type = IdType.AUTO)`、`LocalDateTime`。
- **红线**：Spring Boot **绝不直连 NapCat**。所有 QQ 侧动作（发文本 / 发文件）一律经 LangBot。本计划的 `LangBotClient` 是这条红线的唯一出口。
- **两条契约不混用**：`send_message`（文本）与 `send_file`（文件）是**两个独立方法、两个独立请求体**，不得合并成一个「万能发送」方法。
- 本计划依赖 Plan 2-2 已产出：`ProfileFactService.upsertAuto`、`db/schema.sql`、测试基座 `application-test.yaml`。**先完成 Plan 2-2 再做本计划**。
- 每个 Task 作为一个独立提交。

---

## 〇、契约增补（须同步回 `api_contract.md`）

Phase 3 的 LangBot 需要知道「这条主动消息属于哪个业务对象」，才能在用户回「完成/取消」时路由到正确的 memo。原契约 §12.1 缺此字段，本计划增补两项：

| 位置 | 增补 | 理由 |
|---|---|---|
| §12.1 `send_message` 请求体 | 新增可选字段 `bizRef` (string)，形如 `"memo:123"` | LangBot 据此维护「当前激活提醒 `memo_id` → 用户」映射，实现 QQ 内闭环 |
| §3.1 `POST /api/drafts` 的 `REPLY` payload | 结构由 `{ content, chatId }` 改为 `{ content, targetType, targetId }` | `send_message` 的 `target` 需要 `type`（private/group），草稿表里没有这个信息 |

`targetType` 取值 `private` \| `group`，缺省 `private`；`targetId` 缺省时回退到草稿的 `chatId`，再回退到 `senderId`。

> Task 4 末尾会把这些改动写进 `design/api_contract.md`。

---

## 一、文件结构

```
QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/
├── config/
│   ├── LangBotProperties.java              (新：langbot.base-url 等配置绑定)
│   ├── LangBotConfig.java                  (新：RestClient Bean)
│   └── SchedulingConfig.java               (新：@EnableScheduling)
├── client/
│   └── LangBotClient.java                  (新：sendMessage / sendFile)
├── dto/
│   ├── SendTarget.java                     (新：{type, id})
│   ├── SendMessageRequest.java             (新)
│   ├── SendFileRequest.java                (新)
│   ├── SendResult.java                     (新：{messageId})
│   └── LangBotEnvelope.java                (新：统一响应体反序列化)
├── schedule/
│   └── MemoReminderScheduler.java          (新：到期扫描 + 推送)
├── service/impl/
│   └── DraftServiceImpl.java               (改：dispatch 落地 REPLY/FILE/FACT)
└── ...

QQMessageAssistantJava/src/main/resources/
└── application.yaml                        (改：加 langbot.* 与 app.reminder.*)

QQMessageAssistantJava/src/test/resources/
└── application-test.yaml                   (改：加同名字段，扫描间隔拉长)

QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/
├── client/LangBotClientTest.java           (新：MockRestServiceServer 单测)
├── controller/DraftDispatchTest.java       (新：分发集成测试，mock LangBotClient)
└── schedule/MemoReminderSchedulerTest.java (新)
```

---

### Task 1: LangBot 客户端（`send_message` / `send_file` 两套契约）

**Files:**
- Create: `config/LangBotProperties.java`
- Create: `config/LangBotConfig.java`
- Create: `dto/SendTarget.java`、`dto/SendMessageRequest.java`、`dto/SendFileRequest.java`、`dto/SendResult.java`、`dto/LangBotEnvelope.java`
- Create: `client/LangBotClient.java`
- Modify: `src/main/resources/application.yaml`
- Modify: `src/test/resources/application-test.yaml`
- Test: `src/test/java/com/czx/qqmessageassistantjava/client/LangBotClientTest.java`

**Interfaces:**
- Produces:
  - `LangBotClient.sendMessage(SendMessageRequest req)` → `SendResult`
  - `LangBotClient.sendFile(SendFileRequest req)` → `SendResult`
  - 失败时抛 `BusinessException`（LangBot 返回非 0 code、或网络异常）

- [ ] **Step 1: 写 `config/LangBotProperties.java`**

```java
package com.czx.qqmessageassistantjava.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "langbot")
public class LangBotProperties {

    /** LangBot 基础地址，Phase 3 定实际端口 */
    private String baseUrl = "http://localhost:5300";

    /** 建连超时（毫秒） */
    private int connectTimeoutMs = 3000;

    /** 读超时（毫秒） */
    private int readTimeoutMs = 8000;
}
```

- [ ] **Step 2: 写四个 DTO**

`dto/SendTarget.java`：
```java
package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "发送目标")
public class SendTarget {

    @Schema(description = "private | group")
    private String type;

    @Schema(description = "私聊为 senderId，群聊为 groupId")
    private Long id;
}
```

`dto/SendMessageRequest.java`：
```java
package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "LangBot 主动发文本请求")
public class SendMessageRequest {

    private SendTarget target;

    @Schema(description = "文本内容")
    private String content;

    @Schema(description = "拟人延迟（毫秒）；不传由 LangBot 按字数计算")
    private Integer delayMs;

    @Schema(description = "业务引用，如 memo:123；LangBot 据此做 QQ 内闭环路由")
    private String bizRef;
}
```

`dto/SendFileRequest.java`：
```java
package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "LangBot 主动发文件请求（与发文本是两套契约）")
public class SendFileRequest {

    private SendTarget target;

    @Schema(description = "文件引用：LangBot 本地路径或任务引用")
    private String fileRef;

    @Schema(description = "展示文件名")
    private String fileName;

    @Schema(description = "业务引用")
    private String bizRef;
}
```

`dto/SendResult.java`：
```java
package com.czx.qqmessageassistantjava.dto;

import lombok.Data;

@Data
public class SendResult {

    private String messageId;
}
```

`dto/LangBotEnvelope.java`：
```java
package com.czx.qqmessageassistantjava.dto;

import lombok.Data;

/** LangBot 侧统一响应体 { code, message, data } */
@Data
public class LangBotEnvelope<T> {

    private int code;
    private String message;
    private T data;
}
```

- [ ] **Step 3: 写 `config/LangBotConfig.java`**

```java
package com.czx.qqmessageassistantjava.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class LangBotConfig {

    @Bean
    public RestClient langBotRestClient(LangBotProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
```

- [ ] **Step 4: 写 `client/LangBotClient.java`**

```java
package com.czx.qqmessageassistantjava.client;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.LangBotEnvelope;
import com.czx.qqmessageassistantjava.dto.SendFileRequest;
import com.czx.qqmessageassistantjava.dto.SendMessageRequest;
import com.czx.qqmessageassistantjava.dto.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Spring Boot → LangBot 的唯一出向通道。
 * 红线：Spring Boot 不直连 NapCat，发 QQ 一律经此。
 * 文本与文件是两套独立契约，不合并。
 */
@Component
public class LangBotClient {

    private static final Logger log = LoggerFactory.getLogger(LangBotClient.class);

    private static final ParameterizedTypeReference<LangBotEnvelope<SendResult>> ENVELOPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public LangBotClient(RestClient langBotRestClient) {
        this.restClient = langBotRestClient;
    }

    public SendResult sendMessage(SendMessageRequest request) {
        validateTarget(request.getTarget());
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new BusinessException("发送内容不能为空");
        }
        return post("/api/langbot/send_message", request);
    }

    public SendResult sendFile(SendFileRequest request) {
        validateTarget(request.getTarget());
        if (request.getFileRef() == null || request.getFileRef().isBlank()) {
            throw new BusinessException("fileRef 不能为空");
        }
        return post("/api/langbot/send_file", request);
    }

    private SendResult post(String path, Object body) {
        LangBotEnvelope<SendResult> envelope;
        try {
            envelope = restClient.post()
                    .uri(path)
                    .body(body)
                    .retrieve()
                    .body(ENVELOPE);
        } catch (Exception e) {
            log.error("调用 LangBot {} 失败", path, e);
            throw new BusinessException("调用 LangBot 失败：" + e.getMessage());
        }
        if (envelope == null) {
            throw new BusinessException("LangBot 返回空响应");
        }
        if (envelope.getCode() != 0) {
            throw new BusinessException("LangBot 返回错误：" + envelope.getMessage());
        }
        return envelope.getData();
    }

    private void validateTarget(com.czx.qqmessageassistantjava.dto.SendTarget target) {
        if (target == null || target.getType() == null || target.getId() == null) {
            throw new BusinessException("目标不能为空（需 type 与 id）");
        }
        if (!"private".equals(target.getType()) && !"group".equals(target.getType())) {
            throw new BusinessException("目标类型只能是 private 或 group");
        }
    }
}
```

- [ ] **Step 5: 补配置 `src/main/resources/application.yaml`**

在文件末尾追加（缩进对齐现有顶层键）：

```yaml
langbot:
  base-url: http://localhost:5300
  connect-timeout-ms: 3000
  read-timeout-ms: 8000

app:
  reminder:
    enabled: true
    scan-interval-ms: 60000
    batch-size: 50
```

- [ ] **Step 6: 补测试配置 `src/test/resources/application-test.yaml`**

同样追加，但**扫描间隔拉长**（避免测试期间后台线程干扰），并把提醒功能关掉由测试手动触发：

```yaml
langbot:
  base-url: http://localhost:5300

app:
  reminder:
    scan-interval-ms: 3600000
    batch-size: 50
```

- [ ] **Step 7: 写失败测试 `LangBotClientTest.java`**

这是一个**纯单元测试**（不起 Spring 上下文）：

```java
package com.czx.qqmessageassistantjava.client;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.SendMessageRequest;
import com.czx.qqmessageassistantjava.dto.SendResult;
import com.czx.qqmessageassistantjava.dto.SendTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LangBotClientTest {

    private MockRestServiceServer server;
    private LangBotClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://langbot.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new LangBotClient(builder.build());
    }

    @Test
    void sendMessagePostsToTextEndpoint() {
        server.expect(requestTo("http://langbot.test/api/langbot/send_message"))
                .andExpect(jsonPath("$.content").value("在吗"))
                .andExpect(jsonPath("$.target.type").value("private"))
                .andExpect(jsonPath("$.bizRef").value("memo:1"))
                .andRespond(withSuccess("""
                        {"code":0,"message":"ok","data":{"messageId":"m_1"}}
                        """, MediaType.APPLICATION_JSON));

        SendMessageRequest request = new SendMessageRequest();
        request.setTarget(new SendTarget("private", 1001L));
        request.setContent("在吗");
        request.setBizRef("memo:1");

        SendResult result = client.sendMessage(request);

        assertThat(result.getMessageId()).isEqualTo("m_1");
        server.verify();
    }

    @Test
    void nonZeroCodeBecomesBusinessException() {
        server.expect(requestTo("http://langbot.test/api/langbot/send_message"))
                .andRespond(withSuccess("""
                        {"code":400,"message":"目标不存在","data":null}
                        """, MediaType.APPLICATION_JSON));

        SendMessageRequest request = new SendMessageRequest();
        request.setTarget(new SendTarget("private", 1001L));
        request.setContent("在吗");

        assertThatThrownBy(() -> client.sendMessage(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目标不存在");
    }

    @Test
    void invalidTargetRejectedBeforeHttp() {
        SendMessageRequest request = new SendMessageRequest();
        request.setTarget(new SendTarget("dummy", 1001L));
        request.setContent("在吗");

        assertThatThrownBy(() -> client.sendMessage(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("private 或 group");
        server.verify();   // 一个请求都不该发出
    }
}
```

- [ ] **Step 8: 跑测试**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=LangBotClientTest`
Expected: Tests run: 3, Failures: 0, Errors: 0。

> 若 `MockRestServiceServer.bindTo(RestClient.Builder)` 编译不过，说明 spring-test 版本 < 6.1；本项目 Boot 3.4.12 自带 6.2.x，正常可用。

- [ ] **Step 9: 提交**

```bash
git add QQMessageAssistantJava/src
git commit -m "feat: LangBot 出向客户端（send_message/send_file 两套独立契约）+ 配置绑定"
```

---

### Task 2: 草稿分发落地 —— `REPLY` / `FILE` / `FACT`

**Files:**
- Modify: `service/impl/DraftServiceImpl.java`
- Test: `src/test/java/com/czx/qqmessageassistantjava/controller/DraftDispatchTest.java`

**Interfaces:**
- Consumes: `LangBotClient`（Task 1）、`ProfileFactService.upsertAuto`（Plan 2-2）、`MemoService.createFromDraft`（已存在）
- Produces: `DraftServiceImpl.dispatch(Draft)` 由 `private` 改为**包级可见**（`void dispatch(Draft)`），便于测试直接调用

- [ ] **Step 1: 写失败测试 `DraftDispatchTest.java`**

```java
package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.client.LangBotClient;
import com.czx.qqmessageassistantjava.dto.SendFileRequest;
import com.czx.qqmessageassistantjava.dto.SendMessageRequest;
import com.czx.qqmessageassistantjava.dto.SendResult;
import com.czx.qqmessageassistantjava.entity.Draft;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.entity.ProfileFact;
import com.czx.qqmessageassistantjava.service.DraftService;
import com.czx.qqmessageassistantjava.service.MemoService;
import com.czx.qqmessageassistantjava.service.ProfileFactService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DraftDispatchTest {

    @Autowired
    private DraftService draftService;

    @Autowired
    private MemoService memoService;

    @Autowired
    private ProfileFactService profileFactService;

    @MockitoBean
    private LangBotClient langBotClient;

    private Draft newDraft(String type, Map<String, Object> payload) {
        Draft draft = new Draft();
        draft.setType(type);
        draft.setSenderId(1001L);
        draft.setChatId(2002L);
        draft.setTitle("t");
        draft.setPayload(payload);
        draft.setStatus("PENDING");
        draftService.save(draft);
        return draft;
    }

    @Test
    void replyDispatchSendsText() {
        when(langBotClient.sendMessage(any())).thenReturn(new SendResult());

        Map<String, Object> payload = new HashMap<>();
        payload.put("content", "好的，我看看");
        payload.put("targetType", "private");
        payload.put("targetId", 2002);
        Draft draft = newDraft("REPLY", payload);

        draftService.confirmDraft(draft.getId());

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(langBotClient).sendMessage(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("好的，我看看");
        assertThat(captor.getValue().getTarget().getType()).isEqualTo("private");
        assertThat(captor.getValue().getTarget().getId()).isEqualTo(2002L);
        assertThat(draftService.getById(draft.getId()).getStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void replyDispatchDefaultsTargetToSenderWhenAbsent() {
        when(langBotClient.sendMessage(any())).thenReturn(new SendResult());

        Map<String, Object> payload = new HashMap<>();
        payload.put("content", "默认目标");
        Draft draft = newDraft("REPLY", payload);   // 无 targetType / targetId

        draftService.confirmDraft(draft.getId());

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(langBotClient).sendMessage(captor.capture());
        // 草稿 chatId=2002 优先于 senderId
        assertThat(captor.getValue().getTarget().getId()).isEqualTo(2002L);
        assertThat(captor.getValue().getTarget().getType()).isEqualTo("private");
    }

    @Test
    void fileDispatchSendsFile() {
        when(langBotClient.sendFile(any())).thenReturn(new SendResult());

        Map<String, Object> payload = new HashMap<>();
        payload.put("taskRef", "ft_0001");
        payload.put("fileName", "表单.xlsx");
        payload.put("fileRef", "local://out/ft_0001.xlsx");
        Draft draft = newDraft("FILE", payload);

        draftService.confirmDraft(draft.getId());

        ArgumentCaptor<SendFileRequest> captor = ArgumentCaptor.forClass(SendFileRequest.class);
        verify(langBotClient).sendFile(captor.capture());
        assertThat(captor.getValue().getFileRef()).isEqualTo("local://out/ft_0001.xlsx");
        assertThat(captor.getValue().getFileName()).isEqualTo("表单.xlsx");
        verify(langBotClient, never()).sendMessage(any());
    }

    @Test
    void factDispatchWritesProfileFact() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("factKey", "专业");
        payload.put("factValue", "计算机科学与技术");
        payload.put("confidence", 0.72);
        Draft draft = newDraft("FACT", payload);

        draftService.confirmDraft(draft.getId());

        assertThat(profileFactService.factsMap(1001L)).containsEntry("专业", "计算机科学与技术");
        verifyNoInteractions(langBotClient);
    }

    @Test
    void memoDispatchStillWorks() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("title", "班会");
        payload.put("importance", 3);
        Draft draft = newDraft("MEMO", payload);

        Draft confirmed = draftService.confirmDraft(draft.getId());

        assertThat(confirmed.getStatus()).isEqualTo("CONFIRMED");
        assertThat(memoService.lambdaQuery().eq(Memo::getTitle, "班会").exists()).isTrue();
        verifyNoInteractions(langBotClient);
    }
}
```

> `@MockitoBean` 是 Boot 3.4 的写法（`@MockBean` 已废弃）。包路径 `org.springframework.test.context.bean.override.mockito.MockitoBean`。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=DraftDispatchTest`
Expected: `replyDispatchSendsText`、`fileDispatchSendsFile`、`factDispatchWritesProfileFact` 失败（`dispatch` 目前是空壳，Verification 报 "Wanted but not invoked"）。`memoDispatchStillWorks` 应已通过。

- [ ] **Step 3: 改 `DraftServiceImpl`**

在类顶部补字段与 import：

```java
import com.czx.qqmessageassistantjava.client.LangBotClient;
import com.czx.qqmessageassistantjava.dto.FactUpsertRequest;
import com.czx.qqmessageassistantjava.dto.SendFileRequest;
import com.czx.qqmessageassistantjava.dto.SendMessageRequest;
import com.czx.qqmessageassistantjava.dto.SendTarget;
import com.czx.qqmessageassistantjava.service.ProfileFactService;

import java.math.BigDecimal;
import java.util.Map;

    @Autowired
    private LangBotClient langBotClient;

    @Autowired
    private ProfileFactService profileFactService;
```

把 `dispatch` 方法整体替换为：

```java
    /** 包级可见，便于测试直接调用 */
    void dispatch(Draft draft) {
        switch (draft.getType()) {
            case "MEMO" -> memoService.createFromDraft(draft);
            case "REPLY" -> dispatchReply(draft);
            case "FILE" -> dispatchFile(draft);
            case "FACT" -> dispatchFact(draft);
            default -> throw new BusinessException("未知草稿类型 " + draft.getType());
        }
    }

    private void dispatchReply(Draft draft) {
        Map<String, Object> payload = draft.getPayload();
        SendMessageRequest request = new SendMessageRequest();
        request.setTarget(resolveTarget(payload, draft));
        request.setContent(asString(payload.get("content")));
        request.setBizRef("draft:" + draft.getId());
        langBotClient.sendMessage(request);
    }

    private void dispatchFile(Draft draft) {
        Map<String, Object> payload = draft.getPayload();
        SendFileRequest request = new SendFileRequest();
        request.setTarget(resolveTarget(payload, draft));
        request.setFileRef(asString(payload.get("fileRef")));
        request.setFileName(asString(payload.get("fileName")));
        request.setBizRef("draft:" + draft.getId());
        langBotClient.sendFile(request);
    }

    private void dispatchFact(Draft draft) {
        Map<String, Object> payload = draft.getPayload();
        FactUpsertRequest request = new FactUpsertRequest();
        request.setSenderId(draft.getSenderId());
        request.setFactKey(asString(payload.get("factKey")));
        request.setFactValue(asString(payload.get("factValue")));
        Object confidence = payload.get("confidence");
        if (confidence instanceof Number number) {
            request.setConfidence(new BigDecimal(number.toString()));
        }
        profileFactService.upsertAuto(request);
    }

    /**
     * targetType 缺省 private；targetId 缺省 chatId，再缺省 senderId。
     */
    private SendTarget resolveTarget(Map<String, Object> payload, Draft draft) {
        String type = asString(payload.get("targetType"));
        if (type == null || type.isBlank()) {
            type = "private";
        }
        Long id = asLong(payload.get("targetId"));
        if (id == null) {
            id = draft.getChatId();
        }
        if (id == null) {
            id = draft.getSenderId();
        }
        return new SendTarget(type, id);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            return Long.valueOf(str);
        }
        return null;
    }
```

- [ ] **Step 4: 跑测试**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=DraftDispatchTest`
Expected: Tests run: 5, Failures: 0, Errors: 0。

> 注意 `confirmDraft` 上有 `@Transactional`。若 LangBot 调用抛异常，整个事务回滚、草稿留在 `PENDING` —— 这是**期望行为**（发送失败就不该标记已确认）。但存在一个已知边界：LangBot 实际已发出、只是响应超时，此时回滚会让用户看到「未确认但已发送」。个人项目可接受，Phase 3 若要严格可在 LangBot 侧做幂等（按 `bizRef` 去重）。

- [ ] **Step 5: 提交**

```bash
git add QQMessageAssistantJava/src
git commit -m "feat: 草稿分发落地 —— REPLY/FILE 调 LangBot，FACT 写 profile_fact"
```

---

### Task 3: 定时提醒扫描与主动推送

**Files:**
- Create: `config/SchedulingConfig.java`
- Create: `schedule/MemoReminderScheduler.java`
- Test: `src/test/java/com/czx/qqmessageassistantjava/schedule/MemoReminderSchedulerTest.java`

**Interfaces:**
- Consumes: `LangBotClient.sendMessage`（Task 1）、`MemoReminderMapper`、`MemoService`（已存在）
- Produces: `MemoReminderScheduler.scanOnce()` → `int`（本次成功推送条数），供测试直接调用

> **关于调度器选型**：`optimization_schemes.md` 写的是 Quartz。本计划改用 Spring 内置 `@Scheduled`——个人单机项目下 Quartz 的持久化 JobStore、集群协调全都用不上，只带来配置负担。`@Scheduled` 放在 `MemoReminderScheduler` 类内，日后若要换 Quartz，改动被限制在这一个类里。若要严格按文档走 Quartz，把 `SchedulingConfig` 换成 Quartz 配置并给 `scanOnce()` 套一个 `Job` 即可，业务代码不变。

- [ ] **Step 1: 写 `config/SchedulingConfig.java`**

```java
package com.czx.qqmessageassistantjava.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class SchedulingConfig {
}
```

- [ ] **Step 2: 写失败测试 `MemoReminderSchedulerTest.java`**

```java
package com.czx.qqmessageassistantjava.schedule;

import com.czx.qqmessageassistantjava.client.LangBotClient;
import com.czx.qqmessageassistantjava.dto.SendMessageRequest;
import com.czx.qqmessageassistantjava.dto.SendResult;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.entity.MemoReminder;
import com.czx.qqmessageassistantjava.mapper.MemoReminderMapper;
import com.czx.qqmessageassistantjava.service.MemoService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoReminderSchedulerTest {

    @Autowired
    private MemoReminderScheduler scheduler;

    @Autowired
    private MemoService memoService;

    @Autowired
    private MemoReminderMapper memoReminderMapper;

    @MockitoBean
    private LangBotClient langBotClient;

    private Memo activeMemo(int importance) {
        Memo memo = new Memo();
        memo.setSenderId(1401L);
        memo.setTitle("班会");
        memo.setContent("周四晚 7 点");
        memo.setImportance(importance);
        memo.setStatus("ACTIVE");
        memo.setDueTime(LocalDateTime.now().plusHours(5));
        memoService.save(memo);
        return memo;
    }

    private MemoReminder dueReminder(Long memoId) {
        MemoReminder reminder = new MemoReminder();
        reminder.setMemoId(memoId);
        reminder.setOffsetLabel("2h");
        reminder.setRemindAt(LocalDateTime.now().minusMinutes(1));
        reminder.setStatus("PENDING");
        memoReminderMapper.insert(reminder);
        return reminder;
    }

    @Test
    void dueReminderIsPushedAndMarkedSent() {
        when(langBotClient.sendMessage(any())).thenReturn(new SendResult());
        Memo memo = activeMemo(3);
        MemoReminder reminder = dueReminder(memo.getId());

        int sent = scheduler.scanOnce();

        assertThat(sent).isEqualTo(1);
        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(langBotClient).sendMessage(captor.capture());
        assertThat(captor.getValue().getContent()).contains("班会");
        assertThat(captor.getValue().getBizRef()).isEqualTo("memo:" + memo.getId());
        assertThat(captor.getValue().getTarget().getId()).isEqualTo(1401L);

        assertThat(memoReminderMapper.selectById(reminder.getId()).getStatus()).isEqualTo("SENT");
        assertThat(memoReminderMapper.selectById(reminder.getId()).getSentAt()).isNotNull();
        // 没有剩余待发提醒 → AWAITING_REPLY
        assertThat(memoService.getById(memo.getId()).getStatus()).isEqualTo("AWAITING_REPLY");
    }

    @Test
    void futureReminderNotPickedUp() {
        Memo memo = activeMemo(3);
        MemoReminder reminder = new MemoReminder();
        reminder.setMemoId(memo.getId());
        reminder.setOffsetLabel("2h");
        reminder.setRemindAt(LocalDateTime.now().plusHours(1));
        reminder.setStatus("PENDING");
        memoReminderMapper.insert(reminder);

        assertThat(scheduler.scanOnce()).isZero();
        verifyNoInteractions(langBotClient);
    }

    @Test
    void memoDoneCancelsReminderInsteadOfSending() {
        Memo memo = activeMemo(3);
        memo.setStatus("DONE");
        memoService.updateById(memo);
        MemoReminder reminder = dueReminder(memo.getId());

        assertThat(scheduler.scanOnce()).isZero();
        verifyNoInteractions(langBotClient);
        assertThat(memoReminderMapper.selectById(reminder.getId()).getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void sendFailureKeepsReminderPending() {
        when(langBotClient.sendMessage(any()))
                .thenThrow(new com.czx.qqmessageassistantjava.common.BusinessException("LangBot 挂了"));
        Memo memo = activeMemo(3);
        MemoReminder reminder = dueReminder(memo.getId());

        assertThat(scheduler.scanOnce()).isZero();
        assertThat(memoReminderMapper.selectById(reminder.getId()).getStatus()).isEqualTo("PENDING");
    }

    @Test
    void overdueSweepMarksExpiredMemos() {
        Memo memo = activeMemo(3);
        memo.setStatus("ACTIVE");
        memo.setDueTime(LocalDateTime.now().minusHours(1));
        memoService.updateById(memo);

        scheduler.scanOnce();

        assertThat(memoService.getById(memo.getId()).getStatus()).isEqualTo("OVERDUE");
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=MemoReminderSchedulerTest`
Expected: 编译失败（`MemoReminderScheduler` 还不存在）。

- [ ] **Step 4: 写 `schedule/MemoReminderScheduler.java`**

```java
package com.czx.qqmessageassistantjava.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czx.qqmessageassistantjava.client.LangBotClient;
import com.czx.qqmessageassistantjava.dto.SendMessageRequest;
import com.czx.qqmessageassistantjava.dto.SendTarget;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.entity.MemoReminder;
import com.czx.qqmessageassistantjava.mapper.MemoReminderMapper;
import com.czx.qqmessageassistantjava.service.MemoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 到期提醒扫描 → 经 LangBot 主动推送到 QQ。
 * 调度只是触发器，业务全在 {@link #scanOnce()}，便于测试与日后换调度器。
 */
@Component
public class MemoReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemoReminderScheduler.class);

    /** 这些状态下才允许推送提醒 */
    private static final Set<String> PUSHABLE = Set.of("ACTIVE", "REMINDED", "AWAITING_REPLY");

    @Autowired
    private MemoReminderMapper memoReminderMapper;

    @Autowired
    private MemoService memoService;

    @Autowired
    private LangBotClient langBotClient;

    @Value("${app.reminder.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.reminder.scan-interval-ms:60000}")
    public void scan() {
        try {
            int sent = scanOnce();
            if (sent > 0) {
                log.info("提醒扫描完成，推送 {} 条", sent);
            }
        } catch (Exception e) {
            log.error("提醒扫描异常", e);
        }
    }

    @Transactional
    public int scanOnce() {
        List<MemoReminder> due = memoReminderMapper.selectList(
                new LambdaQueryWrapper<MemoReminder>()
                        .eq(MemoReminder::getStatus, "PENDING")
                        .le(MemoReminder::getRemindAt, LocalDateTime.now())
                        .orderByAsc(MemoReminder::getRemindAt)
                        .last("LIMIT " + batchSize));

        int sent = 0;
        for (MemoReminder reminder : due) {
            Memo memo = memoService.getById(reminder.getMemoId());
            if (memo == null || !PUSHABLE.contains(memo.getStatus())) {
                cancel(reminder);
                continue;
            }
            try {
                langBotClient.sendMessage(buildRequest(memo));
            } catch (Exception e) {
                // 保持 PENDING，下一轮重试
                log.warn("提醒推送失败 reminderId={} memoId={}", reminder.getId(), memo.getId(), e);
                continue;
            }
            reminder.setStatus("SENT");
            reminder.setSentAt(LocalDateTime.now());
            memoReminderMapper.updateById(reminder);
            refreshMemoStatus(memo);
            sent++;
        }
        overdueSweep();
        return sent;
    }

    private SendMessageRequest buildRequest(Memo memo) {
        SendMessageRequest request = new SendMessageRequest();
        // 提醒是发给用户本人的，固定走私聊
        request.setTarget(new SendTarget("private", memo.getSenderId()));
        request.setContent(composeText(memo));
        request.setBizRef("memo:" + memo.getId());
        return request;
    }

    private String composeText(Memo memo) {
        StringBuilder sb = new StringBuilder();
        sb.append("【提醒·").append(memo.getImportance()).append("级】").append(memo.getTitle());
        if (memo.getContent() != null && !memo.getContent().isBlank()) {
            sb.append('\n').append(memo.getContent());
        }
        if (memo.getDueTime() != null) {
            sb.append("\n时间：").append(memo.getDueTime());
        }
        sb.append("\n回「完成」或「取消」即可收尾");
        return sb.toString();
    }

    /** 还有待发提醒 → REMINDED；全部发完 → AWAITING_REPLY */
    private void refreshMemoStatus(Memo memo) {
        boolean morePending = memoReminderMapper.exists(
                new LambdaQueryWrapper<MemoReminder>()
                        .eq(MemoReminder::getMemoId, memo.getId())
                        .eq(MemoReminder::getStatus, "PENDING"));
        String next = morePending ? "REMINDED" : "AWAITING_REPLY";
        if (!next.equals(memo.getStatus())) {
            memo.setStatus(next);
            memoService.updateById(memo);
        }
    }

    private void cancel(MemoReminder reminder) {
        reminder.setStatus("CANCELLED");
        memoReminderMapper.updateById(reminder);
    }

    /** 已过 due_time 但仍未收尾的备忘 → OVERDUE */
    private void overdueSweep() {
        List<Memo> overdue = memoService.lambdaQuery()
                .in(Memo::getStatus, PUSHABLE)
                .isNotNull(Memo::getDueTime)
                .lt(Memo::getDueTime, LocalDateTime.now())
                .list();
        for (Memo memo : overdue) {
            memo.setStatus("OVERDUE");
            memoService.updateById(memo);
        }
    }
}
```

- [ ] **Step 5: 跑测试**

Run: `cd QQMessageAssistantJava && mvn test -Dtest=MemoReminderSchedulerTest`
Expected: Tests run: 5, Failures: 0, Errors: 0。

> 若 `overdueSweepMarksExpiredMemos` 失败，检查 `PUSHABLE` 是否包含 `ACTIVE`，以及 `.in(...)` 的字段名是否为 `status`。

- [ ] **Step 6: 提交**

```bash
git add QQMessageAssistantJava/src
git commit -m "feat: 到期提醒扫描与主动推送（@Scheduled + LangBot send_message + 状态机回写）"
```

---

### Task 4: 全量回归 + 契约增补回写

**Files:**
- Modify: `design/api_contract.md`（回写第〇节的增补）

- [ ] **Step 1: 跑全量测试**

Run: `cd QQMessageAssistantJava && mvn test`
Expected: BUILD SUCCESS，Failures: 0, Errors: 0。

- [ ] **Step 2: 回写契约**

编辑 `design/api_contract.md`：

1. §3.1「`payload` 按 type 的结构」中 `REPLY` 那条，改为：
   ```jsonc
   // REPLY —— 拟人回复草稿
   { "content": "好的，我看看", "targetType": "private", "targetId": 2002 }
   ```
   并补一行说明：`targetType` 取 `private`\|`group`，缺省 `private`；`targetId` 缺省时回退 `chatId`，再回退 `senderId`。

2. §3.3 分发表中把 `MEMO` 行的接入阶段改为「已完成（Plan 2-2）」，`REPLY` / `FILE` / `FACT` 三行改为「已完成（Plan 2-3）」。

3. §12.1 `send_message` 请求体增加一行：
   | `bizRef` | string | 否 | 业务引用，如 `memo:123` / `draft:45`；LangBot 据此维护「当前激活提醒 → memo」映射，实现 QQ 内闭环 |

4. §13 移除已确认项，保留仍待确认项。

- [ ] **Step 3: 提交**

```bash
git add -A
git commit -m "docs: Phase 2-3 完成，回写 REPLY payload 与 send_message bizRef 契约增补"
```

---

## Self-Review 记录

- **契约覆盖**：`api_contract.md` §12 的两个 LangBot 接口由 Task 1 实现；§3.3 的四条分发路径由 Task 2 实现 + 回归；§5.1 的「提醒策略展开」已在既有代码中存在，其**执行侧**（到期推送）由 Task 3 实现。
- **红线核对**：本计划新增的出向调用**只有** `LangBotClient`（Task 1），且只被 `DraftServiceImpl.dispatch` 与 `MemoReminderScheduler` 使用 —— 均属「经 LangBot 发 QQ」，未出现直连 NapCat 的代码。
- **类型一致性**：`SendTarget` / `SendMessageRequest` / `SendFileRequest` / `SendResult` 在 Task 1 定义，Task 2 / Task 3 及各自测试引用名一致；`MemoReminderScheduler.scanOnce()` 返回值 `int`，测试断言 `isZero()` / `isEqualTo(1)` 一致。
- **无占位符**：每个 Step 都给了可直接粘贴的代码/命令。
- **已知边界**：`confirmDraft` 事务内做远程调用（见 Task 2 Step 4 说明）；`overdueSweep` 每轮全表扫 `PUSHABLE` 状态（个人项目量级可忽略）。

---

## Phase 2 完成后

Spring Boot 端全部职责完成。此时端到端链路为：

```
Vue ──HTTP──► Spring Boot ──HTTP──► LangBot ──► NapCat ──► QQ
                    ▲                   │
                    └──── Dify Tool ────┘
```

下一步进入 **Phase 3：LangBot 插件（Python）**，需要产出的文档：

1. LangBot 插件骨架与事件入口（消息收发、LangBot 插件 API 确认）。
2. 前置拦截（关键词 + 正则，规则从 `GET /api/collection-tasks/rules` 热加载）。
3. 拟人延迟实现（`1.5s + 字数 × 0.15s + 抖动`，上限 15s，仅私聊）。
4. 对外暴露 `send_message` / `send_file` 两个 HTTP 端点（供 Spring Boot 调用，含 `bizRef` 幂等）。
5. 消息日志上报（`POST /api/message-logs`，异步 / 可批量）。
6. `memo_id` 激活映射与「完成/取消」QQ 内闭环。
7. 文件任务的下载 / 结构化读 / 写回 / 回传（`openpyxl` / `python-docx` / `pypdf`）+ 状态镜像同步（`POST /api/file-tasks/sync`）。

需要我出这份文档时说一声。
