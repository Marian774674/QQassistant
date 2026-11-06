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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubAuthConfig.class)
@ActiveProfiles("test")
@Transactional
class CollectionTaskCheckFixTest {

    @Autowired
    private MockMvc mockMvc;

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
}
