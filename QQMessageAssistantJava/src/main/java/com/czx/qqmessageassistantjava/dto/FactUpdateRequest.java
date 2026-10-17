package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "事实修改请求（Vue 记忆总控台）")
public class FactUpdateRequest {
    private String factKey;
    private String factValue;
}
