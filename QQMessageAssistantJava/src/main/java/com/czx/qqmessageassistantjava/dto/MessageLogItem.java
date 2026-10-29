package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "消息日志项")
public class MessageLogItem {

    @Schema(description = "发送者ID")
    private Long senderId;
    @Schema(description = "聊天ID")
    private Long chatId;
    @Schema(description = "Dify conversation_id")
    private String conversationId;
    @Schema(description = "平台消息 id")
    private String messageId;
    @Schema(description = "幂等键；相同值重复写入被忽略")
    private String dedupeKey;
    @Schema(description = "内容")
    private String content;
    @Schema(description = "方向",allowableValues = {"IN","OUT"})
    private String direction;
    @Schema(description = "消息类型")
    private String msgType;
}
