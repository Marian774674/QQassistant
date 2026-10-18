package com.czx.qqmessageassistantjava.client;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.SendMessageRequest;
import com.czx.qqmessageassistantjava.dto.SendResult;
import com.czx.qqmessageassistantjava.dto.SendTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LangBotClientTest {

    private MockRestServiceServer server;
    private LangBotClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://langbot.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new LangBotClient(builder.build());
    }

    @Test
    void sendMessagePostsToTextEndpoint() {
        server.expect(requestTo("http://langbot.test/api/langbot/send_message"))
                .andExpect(jsonPath("$.content").value("在吗"))
                .andExpect(jsonPath("$.target.type").value("private"))
                .andExpect(jsonPath("$.bizRef").value("memo:1"))
                .andRespond(withSuccess("""
                        {"code":0,"message":"ok","data":{"messageId":"m_1"}}
                        """, MediaType.APPLICATION_JSON));

        SendMessageRequest request = new SendMessageRequest();
        request.setTarget(new SendTarget("private", 1001L));
        request.setContent("在吗");
        request.setBizRef("memo:1");

        SendResult result = client.sendMessage(request);

        assertThat(result.getMessageId()).isEqualTo("m_1");
        server.verify();
    }

    @Test
    void nonZeroCodeBecomesBusinessException() {
        server.expect(requestTo("http://langbot.test/api/langbot/send_message"))
                .andRespond(withSuccess("""
                        {"code":400,"message":"目标不存在","data":null}
                        """, MediaType.APPLICATION_JSON));

        SendMessageRequest request = new SendMessageRequest();
        request.setTarget(new SendTarget("private", 1001L));
        request.setContent("在吗");

        assertThatThrownBy(() -> client.sendMessage(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目标不存在");
    }

    @Test
    void invalidTargetRejectedBeforeHttp() {
        SendMessageRequest request = new SendMessageRequest();
        request.setTarget(new SendTarget("dummy", 1001L));
        request.setContent("在吗");

        assertThatThrownBy(() -> client.sendMessage(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("private 或 group");
        server.verify();   // 一个请求都不该发出
    }
}
