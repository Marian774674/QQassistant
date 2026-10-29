package com.czx.qqmessageassistantjava.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸门行为：没配置 = 503（fail closed），缺/错 token = 401，对了才放行。
 * 纯单元测试，不启动容器——过滤器的职责只有这几行。
 */
class AgentTokenFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private MockHttpServletRequest post() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/ops");
        request.setRequestURI("/api/agent/ops");
        return request;
    }

    private boolean pass(AgentTokenFilter filter, MockHttpServletRequest request) throws Exception {
        AtomicBoolean passed = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> passed.set(true);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return passed.get();
    }

    @Test
    void unconfiguredTokenFailsClosed() throws Exception {
        AgentTokenFilter filter = new AgentTokenFilter("", objectMapper);
        MockHttpServletRequest request = post();
        request.addHeader(AgentTokenFilter.HEADER, "");

        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean(false);
        filter.doFilter(request, response, (req, res) -> passed.set(true));

        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(readCode(response)).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("未启用");
    }

    @Test
    void missingTokenIsRejected() throws Exception {
        AgentTokenFilter filter = new AgentTokenFilter("secret", objectMapper);
        MockHttpServletRequest request = post();

        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean(false);
        filter.doFilter(request, response, (req, res) -> passed.set(true));

        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(readCode(response)).isEqualTo(401);
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        AgentTokenFilter filter = new AgentTokenFilter("secret", objectMapper);
        MockHttpServletRequest request = post();
        request.addHeader(AgentTokenFilter.HEADER, "secre");

        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean(false);
        filter.doFilter(request, response, (req, res) -> passed.set(true));

        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void correctTokenPasses() throws Exception {
        AgentTokenFilter filter = new AgentTokenFilter("secret", objectMapper);
        MockHttpServletRequest request = post();
        request.addHeader(AgentTokenFilter.HEADER, "secret");

        assertThat(pass(filter, request)).isTrue();
    }

    private int readCode(MockHttpServletResponse response) throws Exception {
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        return body.get("code").asInt();
    }
}
