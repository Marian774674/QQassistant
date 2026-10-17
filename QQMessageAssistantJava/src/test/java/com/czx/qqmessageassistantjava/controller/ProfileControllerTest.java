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
