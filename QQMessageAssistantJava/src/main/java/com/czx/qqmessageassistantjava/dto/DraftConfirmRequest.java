package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "确认草稿请求；只有命名草稿需要 aliases")
public class DraftConfirmRequest {

    @Schema(description = "会话名字，逗号分隔；第一个用于展示", example = "小明,明明")
    private String aliases;
}
