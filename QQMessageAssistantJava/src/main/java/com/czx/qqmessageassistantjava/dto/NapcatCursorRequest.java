package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "推进 NapCat 补拉游标")
@Data
public class NapcatCursorRequest {

    @Schema(description = "GROUP | PRIVATE", allowableValues = {"GROUP", "PRIVATE"})
    private String chatType;

    @Schema(description = "群号；私聊为对方 QQ")
    private Long chatId;

    @Schema(description = "已经成功补写到 message_log 的最新 message_seq")
    private Long lastMessageSeq;
}
