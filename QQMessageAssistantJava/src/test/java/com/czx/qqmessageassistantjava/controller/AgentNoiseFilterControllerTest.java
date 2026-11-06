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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 前置过滤接口契约：走 agent 通道、返回 noise 布尔，规则和整理侧共用一份。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubAuthConfig.class)
@ActiveProfiles("test")
@Transactional
class AgentNoiseFilterControllerTest {

    private static final String TOKEN = "test-token";
    private static final String URL = "/api/agent/noise-filter/check";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void checkNeedsAgentToken() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"……\",\"msgType\":\"text\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void punctuationIsReportedAsNoise() throws Exception {
        mockMvc.perform(post(URL)
                        .header(AgentTokenFilter.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"……\",\"msgType\":\"text\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.noise").value(true))
                .andExpect(jsonPath("$.data.reason").value("punctuation"));
    }

    @Test
    void actionableMessageIsKept() throws Exception {
        mockMvc.perform(post(URL)
                        .header(AgentTokenFilter.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"明天 20:00 班会\",\"msgType\":\"text\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.noise").value(false));
    }

    @Test
    void nonTextMessageIsAlwaysKept() throws Exception {
        mockMvc.perform(post(URL)
                        .header(AgentTokenFilter.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"\",\"msgType\":\"file\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.noise").value(false));
    }

    @Test
    void emptyTextIsNoise() throws Exception {
        mockMvc.perform(post(URL)
                        .header(AgentTokenFilter.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.noise").value(true))
                .andExpect(jsonPath("$.data.reason").value("blank"));
    }
}
