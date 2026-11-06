package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 设置页「消息整理 · 噪音过滤」的写入请求；字段为 null 表示这一项不改。 */
@Data
@Schema(description = "噪音过滤配置")
public class NoiseFilterUpdateRequest {

    @Schema(description = "总开关")
    private Boolean enabled;

    @Schema(description = "逗号分隔的整句噪音词")
    private String keywords;

    @Schema(description = "额外噪音正则，留空表示停用")
    private String regex;

    @Schema(description = "命中即保留的正则，留空表示停用")
    private String keepRegex;
}
