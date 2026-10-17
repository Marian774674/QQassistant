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
class StyleProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void updateThenGetReturnsLatest() throws Exception {
        mockMvc.perform(post("/api/style-profile/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"senderId": 1101, "profilePrompt": "说话简短、常用'嗯/好的'"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.profilePrompt").value("说话简短、常用'嗯/好的'"))
                .andExpect(jsonPath("$.data.lastSummarizedAt").exists());

        mockMvc.perform(get("/api/style-profile/1101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profilePrompt").value("说话简短、常用'嗯/好的'"));
    }

    @Test
    void updateTwiceKeepsSingleRow() throws Exception {
        for (String prompt : new String[]{"第一版", "第二版"}) {
            mockMvc.perform(post("/api/style-profile/update")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"senderId\": 1102, \"profilePrompt\": \"" + prompt + "\"}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/style-profile/1102"))
                .andExpect(jsonPath("$.data.profilePrompt").value("第二版"));
    }

    @Test
    void blankPromptRejected() throws Exception {
        mockMvc.perform(post("/api/style-profile/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"senderId\": 1103, \"profilePrompt\": \"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
