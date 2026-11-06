package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.config.AgentTokenFilter;
import com.czx.qqmessageassistantjava.support.StubAuthConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账本接口契约：写入走 agent 通道（必须带 token），读取要登录且只读。
 * 业务参数错误返回 HTTP 200 + code!=0，和插件 HTTP 客户端的判定口径一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubAuthConfig.class)
@ActiveProfiles("test")
@Transactional
class TokenUsageControllerTest {

    private static final String TOKEN = "test-token";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void agentWriteNeedsToken() throws Exception {
        mockMvc.perform(post("/api/agent/token-usage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"plugin","task":"chat","totalTokens":12}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void recordThenSummaryAndRecent() throws Exception {
        mockMvc.perform(post("/api/agent/token-usage")
                        .header(AgentTokenFilter.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"plugin","task":"chat","model":"qwen-plus",
                                 "promptTokens":10,"completionTokens":2,"totalTokens":12,
                                 "chatId":987654}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isNumber());

        mockMvc.perform(get("/api/token-usage/recent").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.chatId == 987654)].task",
                        org.hamcrest.Matchers.hasItem("chat")))
                .andExpect(jsonPath("$.data[?(@.chatId == 987654)].totalTokens",
                        org.hamcrest.Matchers.hasItem(12)));

        mockMvc.perform(get("/api/token-usage/summary").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.days").value(7))
                .andExpect(jsonPath("$.data.byTask").isArray())
                .andExpect(jsonPath("$.data.byChat").isArray())
                .andExpect(jsonPath("$.data.byChat[?(@.chatId == 987654)].kind",
                        org.hamcrest.Matchers.hasItem("chat")));
    }

    @Test
    void badPayloadIsBusinessErrorNotHttp4xx() throws Exception {
        mockMvc.perform(post("/api/agent/token-usage")
                        .header(AgentTokenFilter.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"someone","task":"chat","totalTokens":12}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void summaryWindowIsClamped() throws Exception {
        mockMvc.perform(get("/api/token-usage/summary").param("days", "9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days").value(90));
    }
}
