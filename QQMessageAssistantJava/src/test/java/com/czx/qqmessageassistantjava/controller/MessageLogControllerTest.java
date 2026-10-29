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
}
