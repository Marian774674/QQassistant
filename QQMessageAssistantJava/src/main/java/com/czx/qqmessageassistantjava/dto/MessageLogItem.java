package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "消息日志项")
public class MessageLogItem {

    @Schema(description = "发送者ID")
    private Long senderId;
    @Schema(description = "发送者昵称；拿不到可省")
    private String senderName;
    @Schema(description = "聊天ID")
    private Long chatId;
    @Schema(description = "群名；私聊可省")
    private String chatName;
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
    @Schema(description = "仅 OUT：TASK|CHAT", allowableValues = {"TASK", "CHAT"})
    private String replyKind;
}
