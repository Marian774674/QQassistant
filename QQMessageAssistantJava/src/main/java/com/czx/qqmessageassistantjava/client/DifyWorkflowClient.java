package com.czx.qqmessageassistantjava.client;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.config.DifyProperties;
import com.czx.qqmessageassistantjava.dto.TokenUsageRecordRequest;
import com.czx.qqmessageassistantjava.service.TokenUsageService;
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
 * 顺带在成功响应里读 {@code total_tokens} 记一笔账——这是唯一能拿到用量的位置，
 * 但记账失败绝不能影响整理 / 归纳 / 问答本身。
 */
@Component
public class DifyWorkflowClient {

    private static final Logger log = LoggerFactory.getLogger(DifyWorkflowClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final DifyProperties properties;
    private final TokenUsageService tokenUsageService;

    public DifyWorkflowClient(@Qualifier("difyRestClient") RestClient restClient,
                              DifyProperties properties,
                              TokenUsageService tokenUsageService) {
        this.restClient = restClient;
        this.properties = properties;
        this.tokenUsageService = tokenUsageService;
    }

    public Map<String, Object> run(String task, Map<String, Object> extraInputs) {
        return run(task, extraInputs, null);
    }

    /**
     * @param chatId 这次调用属于哪个群；私聊和问答类任务传 null。仅用于账本，不进 Dify 输入
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(String task, Map<String, Object> extraInputs, Long chatId) {
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
        recordUsage(response, task, chatId);
        return outputsOf(response, task);
    }

    /**
     * Workflow 的 blocking 响应只给 {@code data.total_tokens}，不拆 prompt/completion，
     * 也不回传模型名。拆账只有插件侧的 chat-messages 才有。
     */
    @SuppressWarnings("unchecked")
    private void recordUsage(Map<String, Object> response, String task, Long chatId) {
        if (response == null) {
            return;
        }
        try {
            Object dataNode = response.get("data");
            if (!(dataNode instanceof Map<?, ?> data)) {
                return;
            }
            int total = intValue(data.get("total_tokens"));
            if (total <= 0) {
                return;
            }
            TokenUsageRecordRequest record = new TokenUsageRecordRequest();
            record.setSource("spring");
            record.setTask(task);
            record.setModel(properties.getModel());
            record.setTotalTokens(total);
            record.setChatId(chatId);
            tokenUsageService.record(record);
        } catch (Exception e) {
            // 账本是旁路边：库没迁移 / 表被锁 / 参数异常，都不能让业务调用失败
            log.warn("记录 token 用量失败 task={}：{}", task, e.toString());
        }
    }

    private static int intValue(Object raw) {
        if (raw instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        try {
            return raw == null ? 0 : Math.max(0, Integer.parseInt(raw.toString().trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
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
