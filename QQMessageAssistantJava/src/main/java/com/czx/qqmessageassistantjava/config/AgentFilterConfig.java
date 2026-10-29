package com.czx.qqmessageassistantjava.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class AgentFilterConfig {

    @Bean
    public FilterRegistrationBean<AgentTokenFilter> agentTokenFilter(AgentProperties properties,
                                                                   ObjectMapper objectMapper) {
        FilterRegistrationBean<AgentTokenFilter> registration =
                new FilterRegistrationBean<>(new AgentTokenFilter(properties.getToken(), objectMapper));
        registration.addUrlPatterns("/api/agent/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
