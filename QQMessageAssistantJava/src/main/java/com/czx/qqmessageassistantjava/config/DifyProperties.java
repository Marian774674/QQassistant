package com.czx.qqmessageassistantjava.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 「记忆与整理」Dify Workflow 的接入参数。
 *
 * <p>和对话用的 Chatflow 是两个独立的 Dify 应用，所以 base-url / api-key 单独配置，
 * 不能复用 {@code MessageController} 里写死的那一套。
 */
@Data
@Component
@ConfigurationProperties(prefix = "dify.workflow")
public class DifyProperties {

    private String baseUrl = "http://127.0.0.1/v1";

    /** 留空表示没配好；调用时会直接报错，不静默降级 */
    private String apiKey = "";

    private int timeoutMs = 60000;
}
