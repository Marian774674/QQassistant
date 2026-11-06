package com.czx.qqmessageassistantjava.auth;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.config.AgentProperties;
import com.czx.qqmessageassistantjava.config.AgentTokenFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 保护 {@code /api/**}：没有有效 Redis 会话就直接 401。
 * Agent 入口由 {@code AgentTokenFilter} 单独校验，不在这里拦；
 * 插件调用业务接口时带同一个 {@code X-Agent-Token}，这里放行。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;

    public AuthInterceptor(AuthService authService,
                           ObjectMapper objectMapper,
                           AgentProperties agentProperties) {
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.agentProperties = agentProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (isAgent(request)) {
            // 插件不是人，没有浏览器会话；它用共享 token 走服务端调用
            request.setAttribute(AuthService.REQUEST_USER_ATTRIBUTE, "agent");
            return true;
        }

        String token = resolveToken(request);
        String username = authService.validate(token);
        if (username == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), Result.fail(401, "未登录或登录已过期"));
            return false;
        }

        request.setAttribute(AuthService.REQUEST_USER_ATTRIBUTE, username);
        request.setAttribute(AuthService.REQUEST_TOKEN_ATTRIBUTE, token);
        return true;
    }

    public static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        String fallback = request.getHeader("X-Auth-Token");
        return fallback == null ? null : fallback.trim();
    }

    private boolean isAgent(HttpServletRequest request) {
        return AgentTokenFilter.matches(request, agentProperties.getToken());
    }
}
