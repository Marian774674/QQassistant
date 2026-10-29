package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.config.AgentTokenFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约要点：业务拒绝必须是 HTTP 200 + code!=0，Dify 的成功分支才能把中文原因念给用户；
 * 只有鉴权/基础设施才走非 2xx（走 OP_FAILED 的通用道歉）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AgentOpsControllerTest {

    private static final String TOKEN = "test-token";

    @Autowired
    private MockMvc mockMvc;

    private org.springframework.test.web.servlet.RequestBuilder ops(String body) {
        return post("/api/agent/ops")
                .contentType(MediaType.APPLICATION_JSON)
                .header(AgentTokenFilter.HEADER, TOKEN)
                .content(body);
    }

    @Test
    void createReturnsOkEnvelopeWithMessageAndMemoId() throws Exception {
        String body = """
                {"eventId":"%s","op":"memo.create","senderId":"8811",
                 "title":"班会","content":"明天下午 2 点 教学楼 S109","importance":"4"}
                """.formatted(UUID.randomUUID());

        mockMvc.perform(ops(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("记下了：")))
                .andExpect(jsonPath("$.data.memoId").isNumber())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.replayed").value(false));
    }

    @Test
    void businessRejectionIsHttp200WithNonZeroCode() throws Exception {
        String body = """
                {"eventId":"%s","op":"memo.delete","senderId":"8811"}
                """.formatted(UUID.randomUUID());

        mockMvc.perform(ops(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("op")));
    }

    @Test
    void closingSomeoneElsesMemoIsRejectedWithoutLeakingExistence() throws Exception {
        String body = """
                {"eventId":"%s","op":"memo.close","senderId":"8811","memoId":"99999999","status":"DONE"}
                """.formatted(UUID.randomUUID());

        mockMvc.perform(ops(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("备忘录不存在"));
    }

    @Test
    void missingTokenIsRejectedBeforeTheController() throws Exception {
        mockMvc.perform(post("/api/agent/ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"x\",\"op\":\"memo.create\",\"senderId\":\"1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }
}
