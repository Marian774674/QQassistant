package com.czx.qqmessageassistantjava.config;

import com.czx.qqmessageassistantjava.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * /api/agent/* 的鉴权。
 *
 * 只拦这一个前缀：其余接口是「只在本机/局域网用」的历史状态，不在本次范围内改动，
 * 但新开的写入口必须有闸门。
 */
public class AgentTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Agent-Token";

    private final String expectedToken;

    private final ObjectMapper objectMapper;

    public AgentTokenFilter(String expectedToken, ObjectMapper objectMapper) {
        this.expectedToken = expectedToken;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expectedToken == null || expectedToken.isBlank()) {
            reject(response, 503, "agent api 未启用：未配置 app.agent.token");
            return;
        }
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expectedToken.getBytes(StandardCharsets.UTF_8))) {
            reject(response, 401, "token 无效");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(status, message)));
    }
}
