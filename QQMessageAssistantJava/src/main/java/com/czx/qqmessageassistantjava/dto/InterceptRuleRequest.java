package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "拦截规则请求参数")
public class InterceptRuleRequest {

    private String name;
    @Schema(description = "拦截关键词")
    private String keywords;
    @Schema(description = "拦截正则")
    private String regex;
    @Schema(description = "回复模板")
    private String replyTemplate;
    @Schema(description = "优先级")
    private Integer priority;
    @Schema(description = "是否启用")
    private Boolean enabled;

}
