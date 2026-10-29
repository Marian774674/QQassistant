package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Schema(description = "文件任务状态镜像同步请求（LangBot → Spring Boot）")
public class FileTaskSyncRequest {

    @Schema(description = "LangBot 侧 taskId，幂等键")
    private String taskRef;

    private Long senderId;

    private Long chatId;

    @Schema(description = "触发任务的 Dify conversation_id")
    private String conversationId;

    private String fileName;

    @Schema(description = "用户原始填写要求")
    private String request;

    @Schema(description = "等待文件时挂起的要求")
    private String pendingRequest;

    @Schema(description = "PENDING|PROCESSING|NEED_INPUT|AWAITING_CONFIRM|SENT|FAILED|CANCELLED")
    private String status;

    @Schema(description = "Dify 判定仍缺的字段")
    private List<String> missing;

    @Schema(description = "填写置信度 0-1")
    private BigDecimal confidence;

    @Schema(description = "是否强制人工确认")
    private Boolean needsConfirm;

    @Schema(description = "输入文件引用")
    private String fileRef;

    @Schema(description = "产物文件引用")
    private String outputRef;

    private Long sourceMessageId;

    private String idempotencyKey;

    @Schema(description = "状态详情（JSON 镜像）")
    private Map<String, Object> detail;
}