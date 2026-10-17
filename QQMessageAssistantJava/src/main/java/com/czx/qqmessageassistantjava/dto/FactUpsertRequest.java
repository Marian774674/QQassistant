package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "事实写入请求 (Dify 高置信)")
public class FactUpsertRequest {

    @Schema(description = "归属用户（仅'本人'才高置信写）")
    private Long senderId;
    @Schema(description = "事实键")
    private String factKey;
    @Schema(description = "事实值")
    private String factValue;
    @Schema(description = "置信度 0-1")
    private BigDecimal confidence;
}
