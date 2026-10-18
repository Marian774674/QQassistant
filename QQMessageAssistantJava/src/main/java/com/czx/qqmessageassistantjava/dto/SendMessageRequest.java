package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "LangBot 主动发文本请求")
public class SendMessageRequest {

    private SendTarget target;

    @Schema(description = "文本内容")
    private String content;

    @Schema(description = "拟人延迟（毫秒）；不传由 LangBot 按字数计算")
    private Integer delayMs;

    @Schema(description = "业务引用，如 memo:123；LangBot 据此做 QQ 内闭环路由")
    private String bizRef;
}