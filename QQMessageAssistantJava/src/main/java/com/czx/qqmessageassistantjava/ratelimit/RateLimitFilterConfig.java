package com.czx.qqmessageassistantjava.ratelimit;

import com.czx.qqmessageassistantjava.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class RateLimitFilterConfig {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            RedisTokenBucket bucket,
            ObjectMapper objectMapper,
            AgentProperties agentProperties,
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.capacity:60}") int capacity,
            @Value("${app.rate-limit.refill-per-second:1}") double refillPerSecond,
            @Value("${app.rate-limit.key-ttl-seconds:120}") int ttlSeconds) {
        RateLimitFilter filter = new RateLimitFilter(
                bucket, objectMapper, enabled, capacity, refillPerSecond, ttlSeconds,
                agentProperties.getToken());
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        // 在 AgentTokenFilter（+10）之后：坏 token 先被挡掉，不再消耗限流桶
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
