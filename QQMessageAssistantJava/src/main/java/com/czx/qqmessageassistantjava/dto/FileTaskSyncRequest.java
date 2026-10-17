package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "文件任务状态镜像同步请求（LangBot → Spring Boot）")
public class FileTaskSyncRequest {

    @Schema(description = "LangBot 侧 taskId，幂等键")
    private String taskRef;

    private Long senderId;

    private Long chatId;

    private String fileName;

    @Schema(description = "PENDING|PROCESSING|NEED_INPUT|AWAITING_CONFIRM|SENT|FAILED|CANCELLED")
    private String status;

    @Schema(description = "状态详情（JSON 镜像）")
    private Map<String, Object> detail;
}