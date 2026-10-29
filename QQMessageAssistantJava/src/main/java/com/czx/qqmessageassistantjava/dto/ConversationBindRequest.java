package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "绑定 QQ 会话与 Dify conversation_id")
public class ConversationBindRequest {

    @Schema(description = "LangBot bot UUID")
    private String botUuid;

    @Schema(description = "private / group")
    private String chatType;

    @Schema(description = "私聊留空或 0；群聊为真实群号")
    private Long chatId;

    private Long senderId;

    @Schema(description = "Dify 返回的 conversation_id；reset 时可为空")
    private String conversationId;
}
