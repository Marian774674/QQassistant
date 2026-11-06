package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "会话称呼修改请求")
public class ChatAliasRequest {

    @Schema(description = "群号；私聊存对方 QQ")
    private Long chatId;

    @Schema(description = "GROUP | PRIVATE")
    private String kind;

    @Schema(description = "逗号分隔；第一个用于展示")
    private String aliases;
}
