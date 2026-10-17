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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void putThenGetRoundTrip() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary_frequency\": \"WEEKLY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));

        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary_frequency").value("WEEKLY"));
    }

    @Test
    void putTwiceOverwritesNotDuplicates() throws Exception {
        for (String value : new String[]{"DAILY_7D", "DAILY_TODAY"}) {
            mockMvc.perform(put("/api/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"summary_frequency\": \"" + value + "\"}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/settings"))
                .andExpect(jsonPath("$.data.summary_frequency").value("DAILY_TODAY"))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void emptyBodyRejected() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
