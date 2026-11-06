package com.czx.qqmessageassistantjava.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import com.czx.qqmessageassistantjava.support.StubAuthConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubAuthConfig.class)
@ActiveProfiles("test")
@Transactional
class MessageLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void duplicateDedupeKeyIsSkipped() throws Exception {
        mockMvc.perform(post("/api/message-logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"senderId": 1401, "direction": "IN", "msgType": "text",
                                  "content": "在吗", "conversationId": "conv-log", "dedupeKey": "in:1"},
                                 {"senderId": 1401, "direction": "IN", "msgType": "text",
                                  "content": "在吗", "conversationId": "conv-log", "dedupeKey": "in:1"},
                                 {"senderId": 1401, "direction": "OUT", "msgType": "text",
                                  "content": "在", "conversationId": "conv-log",
                                  "messageId": "m9", "dedupeKey": "out:1"}]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(2));

        mockMvc.perform(get("/api/message-logs")
                        .param("senderId", "1401")
                        .param("conversationId", "conv-log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].messageId").value("m9"));
    }

    @Test
    void listFiltersByDirection() throws Exception {
        mockMvc.perform(post("/api/message-logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"senderId": 1402, "direction": "IN", "msgType": "text", "content": "a"},
                                 {"senderId": 1402, "direction": "OUT", "msgType": "text", "content": "b"}]
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/message-logs")
                        .param("senderId", "1402")
                        .param("direction", "OUT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].content").value("b"));
    }

    @Test
    void listRejectsInvalidDirection() throws Exception {
        mockMvc.perform(get("/api/message-logs").param("direction", "SIDEWAYS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * 补拉回来的消息必须落在它原本的时间点上。
     *
     * <p>如果按写入时刻落库，一次掉线补回的两百条历史会全部挤在「现在」，
     * 整理的时间窗、夜间归纳的批次、检索的时间排序全部失真。
     * 传的是 epoch 而不是墙钟字符串：插件跑在容器里，时区未必和 JVM 一致。
     */
    @Test
    void backfilledMessageKeepsItsOriginalTime() throws Exception {
        long epoch = LocalDateTime.of(2026, 1, 2, 3, 4, 5)
                .atZone(ZoneId.systemDefault())
                .toEpochSecond();

        mockMvc.perform(post("/api/message-logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"senderId": 1403, "direction": "IN", "msgType": "text",
                                  "content": "补回来的消息", "messageId": "m-1403",
                                  "dedupeKey": "msg:m-1403", "createdAtEpochSeconds": %d}]
                                """.formatted(epoch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));

        mockMvc.perform(get("/api/message-logs").param("senderId", "1403"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-01-02T03:04:05"));
    }
}
