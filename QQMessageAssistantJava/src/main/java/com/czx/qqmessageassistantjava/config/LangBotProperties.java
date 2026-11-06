package com.czx.qqmessageassistantjava.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "langbot")
public class LangBotProperties {

    /** 由 application.yaml 的 ${LANGBOT_BRIDGE_URL} 注入；这里只留一个中性的本机兜底 */
    private String baseUrl = "http://127.0.0.1:5301";

    private int connectTimeoutMs = 3000;

    private int readTimeoutMs = 8000;

}
