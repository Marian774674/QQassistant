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
    void syncCarriesPhase3Fields() throws Exception {
        mockMvc.perform(post("/api/file-tasks/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskRef": "ft_0003", "senderId": 1303, "chatId": 2002,
                                 "conversationId": "conv-1", "fileName": "表.xlsx",
                                 "request": "把姓名填上", "pendingRequest": "姓名、学号",
                                 "status": "AWAITING_CONFIRM", "missing": ["学号"],
                                 "confidence": 0.62, "needsConfirm": true,
                                 "fileRef": "local:///tmp/a.xlsx", "outputRef": "local:///tmp/b.xlsx",
                                 "idempotencyKey": "qq:msg-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.data.request").value("把姓名填上"))
                .andExpect(jsonPath("$.data.pendingRequest").value("姓名、学号"))
                .andExpect(jsonPath("$.data.missing[0]").value("学号"))
                .andExpect(jsonPath("$.data.needsConfirm").value(true))
                .andExpect(jsonPath("$.data.fileRef").value("local:///tmp/a.xlsx"))
                .andExpect(jsonPath("$.data.outputRef").value("local:///tmp/b.xlsx"))
                .andExpect(jsonPath("$.data.idempotencyKey").value("qq:msg-1"));

        mockMvc.perform(get("/api/file-tasks").param("senderId", "1303"))
                .andExpect(jsonPath("$.data[0].conversationId").value("conv-1"))
                .andExpect(jsonPath("$.data[0].missing[0]").value("学号"));
    }

    @Test
    void partialSyncKeepsEarlierFields() throws Exception {
        mockMvc.perform(post("/api/file-tasks/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskRef": "ft_0004", "senderId": 1304, "status": "PROCESSING",
                                 "request": "填学号", "confidence": 0.4}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/file-tasks/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskRef": "ft_0004", "senderId": 1304, "status": "SENT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SENT"))
                .andExpect(jsonPath("$.data.request").value("填学号"))
                .andExpect(jsonPath("$.data.confidence").value(0.4));
    }

    @Test
    void listRejectsInvalidStatusFilter() throws Exception {
        mockMvc.perform(get("/api/file-tasks").param("status", "NOPE"))
                .andExpect(status().isBadRequest());
    }
}
