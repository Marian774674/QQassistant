package com.czx.qqmessageassistantjava.ratelimit;

import com.czx.qqmessageassistantjava.auth.AuthInterceptor;
import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.config.AgentTokenFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 接口限流。按会话 token 分桶；未登录请求按 IP 分桶。
 * Agent 回调由 {@code AgentTokenFilter} 单独鉴权，这里直接放行。
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String PREFIX = "rate:api:";

    private final RedisTokenBucket bucket;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int capacity;
    private final double refillPerSecond;
    private final int ttlSeconds;
    private final String agentToken;

    public RateLimitFilter(RedisTokenBucket bucket,
                           ObjectMapper objectMapper,
                           boolean enabled,
                           int capacity,
                           double refillPerSecond,
                           int ttlSeconds,
                           String agentToken) {
        this.bucket = bucket;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.ttlSeconds = ttlSeconds;
        this.agentToken = agentToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled
                || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || request.getRequestURI().startsWith("/api/agent/")
                // 插件和 Dify 是内部调用，共用 agent token；限流只防外部请求
                || AgentTokenFilter.matches(request, agentToken)) {
            chain.doFilter(request, response);
            return;
        }

        boolean allowed;
        try {
            allowed = bucket.tryAcquire(
                    PREFIX + identity(request), capacity, refillPerSecond, ttlSeconds);
        } catch (RuntimeException e) {
            // 限流是保护手段，不是业务前置条件：Redis 抖动时宁可放行
            log.warn("接口令牌桶不可用，本次放行：{}", e.getMessage());
            allowed = true;
        }

        if (!allowed) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), Result.fail(429, "请求太频繁，请稍后再试"));
            return;
        }
        chain.doFilter(request, response);
    }

    private static String identity(HttpServletRequest request) {
        String token = AuthInterceptor.resolveToken(request);
        if (token != null && !token.isBlank()) {
            return token;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
