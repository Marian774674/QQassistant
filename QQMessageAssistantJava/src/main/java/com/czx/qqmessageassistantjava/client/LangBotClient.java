package com.czx.qqmessageassistantjava.client;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;


@Component
public class LangBotClient {

    private static final Logger log = LoggerFactory.getLogger(LangBotClient.class);

    private static final ParameterizedTypeReference<LangbotEnvelope<SendResult>> ENVELOPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public LangBotClient(@Qualifier("langBotRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public SendResult sendMessage(SendMessageRequest request) {
        validateTarget(request.getTarget());
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new BusinessException("内容不能为空");
        }
        return post("/api/langbot/send_message", request);
    }

    public SendResult sendFile(SendFileRequest request) {
        validateTarget(request.getTarget());
        if (request.getFileRef() == null || request.getFileRef().isBlank()) {
            throw new BusinessException("fileRef 不能为空");
        }
        return post("/api/langbot/send_file", request);
    }

    private SendResult post(String path, Object body) {
        LangbotEnvelope<SendResult> envelope;
        try {
            envelope = restClient.post()
                    .uri(path)
                    .body(body)
                    .retrieve()
                    .body(ENVELOPE);
        } catch (Exception e) {
            log.error("调用LangBot{}失败", path, e);
            throw new BusinessException("调用LangBot失败" + e.getMessage());
        }
        if (envelope == null) {
            throw new BusinessException("LangBot 返回空响应");
        }
        if (envelope.getCode() != 0) {
            throw new BusinessException("LangBot 返回错误：" + envelope.getMessage());
        }
        return envelope.getData();
    }

    private void validateTarget(SendTarget target) {
        if (target == null || target.getType() == null || target.getId() == null) {
            throw new BusinessException("目标不能为空");
        }
        if (!"private".equals(target.getType()) && !"group".equals(target.getType())) {
            throw new BusinessException("目标类型只能是 private 或 group");
        }
    }

}
