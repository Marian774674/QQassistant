package com.czx.qqmessageassistantjava.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "langbot")
public class LangBotProperties {

    private String baseUrl= "http://192.168.100.128:5300";

    private int connectTimeoutMs = 3000;

    private int readTimeoutMs = 8000;

}
