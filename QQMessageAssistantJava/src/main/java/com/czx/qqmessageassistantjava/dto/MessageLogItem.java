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
    @Schema(description = "内容")
    private String content;
    @Schema(description = "方向",allowableValues = {"IN","OUT"})
    private String direction;
    @Schema(description = "消息类型")
    private String msgType;
}
