package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "回复防重")
public class CollectionTaskRequest {

    @Schema(description = "群聊ID")
    private Long chatId;

    @Schema(description = "任务类型")
    private String taskType;

    @Schema(description = "任务key")
    private String taskKey;
}
