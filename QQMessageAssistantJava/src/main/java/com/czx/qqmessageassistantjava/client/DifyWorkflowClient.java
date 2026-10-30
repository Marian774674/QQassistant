package com.czx.qqmessageassistantjava.client;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.config.DifyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调 Dify 的 Workflow 应用（阻塞模式），返回 {@code data.outputs}。
 *
 * <p>只负责「把输入发过去、把输出拿回来」。语义判断在 Dify，落库与校验在 Spring。
 */
@Component
public class DifyWorkflowClient {

    private static final Logger log = LoggerFactory.getLogger(DifyWorkflowClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final DifyProperties properties;

    public DifyWorkflowClient(@Qualifier("difyRestClient") RestClient restClient,
                              DifyProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> run(String task, Map<String, Object> extraInputs) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new BusinessException("Dify 工作流未配置：dify.workflow.api-key 为空");
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (extraInputs != null) {
            inputs.putAll(extraInputs);
        }
        inputs.put("task", task);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputs", inputs);
        body.put("response_mode", "blocking");
        body.put("user", "spring");

        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri("/workflows/run")
                    .body(body)
                    .retrieve()
                    .body(RESPONSE);
        } catch (Exception e) {
            log.error("调用 Dify 工作流失败 task={}", task, e);
            throw new BusinessException("调用 Dify 工作流失败：" + e.getMessage());
        }
        return outputsOf(response, task);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> outputsOf(Map<String, Object> response, String task) {
        if (response == null) {
            throw new BusinessException("Dify 工作流返回空响应");
        }
        Object dataNode = response.get("data");
        if (!(dataNode instanceof Map<?, ?> data)) {
            throw new BusinessException("Dify 工作流返回格式异常：" + response);
        }
        Object status = data.get("status");
        if (!"succeeded".equals(status)) {
            throw new BusinessException("Dify 工作流失败：" + data.get("error"));
        }
        Object outputs = data.get("outputs");
        if (!(outputs instanceof Map<?, ?> map)) {
            log.warn("Dify 工作流 {} 没有 outputs", task);
            return Map.of();
        }
        return (Map<String, Object>) map;
    }
}
