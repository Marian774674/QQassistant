package com.czx.qqmessageassistantjava.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock
    RedisTokenBucket bucket;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(bucket, new ObjectMapper(), true, 60, 1, 120, "agent-secret");
    }

    @Test
    void passesTheRequestWhenTheBucketHasAToken() throws Exception {
        when(bucket.tryAcquire(anyString(), anyInt(), anyDouble(), anyInt())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/memos");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void returns429WithoutCallingTheChainWhenTheBucketIsEmpty() throws Exception {
        when(bucket.tryAcquire(anyString(), anyInt(), anyDouble(), anyInt())).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/memos");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getContentAsString()).contains("请求太频繁");
    }

    @Test
    void doesNotLimitAgentCallbacks() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/tools/run");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        verifyNoInteractions(bucket);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void doesNotLimitPluginCallsWithTheAgentToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/message-logs");
        request.addHeader("X-Agent-Token", "agent-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        verifyNoInteractions(bucket);
        assertThat(chain.getRequest()).isNotNull();
    }
}
