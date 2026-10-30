package com.czx.qqmessageassistantjava.client;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.config.DifyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DifyWorkflowClientTest {

    private MockRestServiceServer server;
    private DifyWorkflowClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://dify.test");
        server = MockRestServiceServer.bindTo(builder).build();
        DifyProperties properties = new DifyProperties();
        properties.setApiKey("test-key");
        client = new DifyWorkflowClient(builder.build(), properties);
    }

    @Test
    void run_postsBlockingWorkflowAndReturnsOutputs() {
        server.expect(requestTo("http://dify.test/workflows/run"))
                .andExpect(jsonPath("$.response_mode").value("blocking"))
                .andExpect(jsonPath("$.inputs.task").value("digest"))
                .andExpect(jsonPath("$.inputs.chat_id").value("1"))
                .andRespond(withSuccess("""
                        {"data":{"status":"succeeded","outputs":{"items":"[]"}}}
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> out = client.run("digest", Map.of("chat_id", "1"));

        assertThat(out).containsKey("items");
        server.verify();
    }

    @Test
    void run_failedStatusBecomesBusinessException() {
        server.expect(requestTo("http://dify.test/workflows/run"))
                .andRespond(withSuccess("""
                        {"data":{"status":"failed","error":"model quota exceeded"}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.run("digest", Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Dify")
                .hasMessageContaining("model quota exceeded");
    }

    @Test
    void run_withoutApiKeyFailsLoudlyInsteadOfSendingUnauthenticated() {
        DifyWorkflowClient unconfigured = new DifyWorkflowClient(
                RestClient.builder().baseUrl("http://dify.test").build(), new DifyProperties());

        assertThatThrownBy(() -> unconfigured.run("digest", Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("dify.workflow.api-key");
    }

    @Test
    void run_serverErrorBecomesBusinessException() {
        server.expect(requestTo("http://dify.test/workflows/run"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.run("digest", Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Dify");
    }
}
