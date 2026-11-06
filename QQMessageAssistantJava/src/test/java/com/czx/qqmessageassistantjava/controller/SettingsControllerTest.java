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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubAuthConfig.class)
@ActiveProfiles("test")
@Transactional
class SettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private org.springframework.core.env.Environment env;

    @Test
    void localPrivateConfigIsActuallyImported() {
        // spring.config.import 是 optional 的，写错路径不会报错、只会静默连不上库。
        // 本机存在 java.properties 时，直接用 Environment 验证它真被读进来了。
        boolean filePresent = java.nio.file.Files.exists(
                java.nio.file.Path.of("../deploy/java.properties"))
                || java.nio.file.Files.exists(java.nio.file.Path.of("deploy/java.properties"));
        org.junit.jupiter.api.Assumptions.assumeTrue(filePresent, "CI 不建私有配置文件");
        org.junit.jupiter.api.Assertions.assertFalse(
                env.getProperty("DB_URL", "").isBlank(), "DB_URL 没被 spring.config.import 载入");
        org.junit.jupiter.api.Assertions.assertFalse(
                env.getProperty("DB_PASSWORD", "").isBlank(), "DB_PASSWORD 没被载入");
    }

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

    @Test
    void webCannotOverwriteThePasswordHash() throws Exception {
        // 这张表里存着登录口令哈希；给什么写什么等于带会话就能绕过登录改密码
        mockMvc.perform(put("/api/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auth_password_hash\": \"pwned\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/api/settings"))
                .andExpect(jsonPath("$.data.auth_password_hash").doesNotExist());
    }

    @Test
    void webCannotRewindSystemCursors() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memory_cursor\": \"0\", \"last_digest_seen\": \"0\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void noiseFilterKeysAreEditable() throws Exception {
        mockMvc.perform(utNoiseBody())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/settings/noise-filter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keywords").value("666,哈哈哈"))
                .andExpect(jsonPath("$.data.defaultKeywords").exists());
    }

    @Test
    void noiseFilterRejectsBadRegex() throws Exception {
        mockMvc.perform(put("/api/settings/noise-filter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regex\": \"[unclosed\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void noisePreviewRunsOnUnsavedDraft() throws Exception {
        // 草稿把正则改成「什么都不匹配」，出厂规则本会丢掉「呵呵呵」
        mockMvc.perform(post("/api/settings/noise-filter/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"texts":["呵呵呵"],"enabled":true,"regex":"^$"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.items[0].kept").value(true));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder utNoiseBody() {
        return put("/api/settings/noise-filter")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"keywords\":\"666,哈哈哈\","
                        + "\"regex\":\"^(.)\\\\1{2,}$\",\"keepRegex\":\"@\"}");
    }
}
