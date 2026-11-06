package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 插件把 Dify chat-messages 的 metadata.usage 记到 Spring 的账本。 */
@Data
@Schema(description = "一条 token 用量")
public class TokenUsageRecordRequest {

    @Schema(description = "来源：spring|plugin")
    private String source;

    @Schema(description = "任务：digest|extract|ask|chat|file …")
    private String task;

    @Schema(description = "模型名（Dify 不回传时由配置标注）")
    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Long chatId;
}
