package com.czx.qqmessageassistantjava.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 5.5「密钥外置」的护栏：application.yaml 一旦又被谁填回明文，这里立刻红。
 * 只检查仓库里的主配置，不检查本机私有的 deploy/java.properties。
 */
class SecretsHygieneTest {

    private static String yaml() throws IOException {
        try (var in = new ClassPathResource("application.yaml").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void datasourceCredentialsArePlaceholdersWithoutDefaults() throws IOException {
        String text = yaml();
        assertTrue(text.contains("url: ${DB_URL:"), "JDBC 地址必须走 DB_URL");
        assertTrue(text.contains("username: ${DB_USERNAME:"), "账号必须走 DB_USERNAME");
        // 口令不允许带默认值：${DB_PASSWORD:} 可以，${DB_PASSWORD:root} 不行
        assertTrue(text.contains("password: ${DB_PASSWORD:}"), "口令不能有明文默认值");
    }

    @Test
    void difyWorkflowKeyHasNoBakedInValue() throws IOException {
        String text = yaml();
        assertTrue(text.contains("api-key: ${DIFY_WORKFLOW_API_KEY:}"));
        assertFalse(text.matches("(?s).*api-key:\\s*app-[A-Za-z0-9]+.*"),
                "Dify App Key 不能写死在配置里");
    }

    @Test
    void noDeploymentSpecificIpOrKnownTokenPrefixes() throws IOException {
        String text = yaml();
        assertFalse(text.contains("192.168."), "部署专用内网地址不能进主配置");
        assertFalse(text.matches("(?s).*\\blbk_[A-Za-z0-9-]{10,}.*"),
                "LangBot API Key 不能进主配置");
        assertFalse(text.matches("(?s).*\\bapp-[A-Za-z0-9]{20,}.*"),
                "Dify App Key 不能进主配置");
    }
}
