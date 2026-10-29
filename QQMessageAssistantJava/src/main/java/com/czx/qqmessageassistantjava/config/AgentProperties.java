package com.czx.qqmessageassistantjava.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Dify 直连后端所需的配置。
 *
 * token 走环境变量（{@code AGENT_OPS_TOKEN}），不写进仓库；留空时 /api/agent/* 一律 503，
 * 也就是说「没配置 = 功能关闭」，不会意外开一个无鉴权的写入口。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.agent")
public class AgentProperties {

    private String token = "";
}
