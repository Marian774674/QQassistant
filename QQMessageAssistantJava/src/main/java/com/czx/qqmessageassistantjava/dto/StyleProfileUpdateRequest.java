package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "风格画像写回请求（Dify 总结工作流）")
public class StyleProfileUpdateRequest {
    @Schema(description = "用户")
    private Long senderId;

    @Schema(description = "新的画像正文")
    private String profilePrompt;
}
