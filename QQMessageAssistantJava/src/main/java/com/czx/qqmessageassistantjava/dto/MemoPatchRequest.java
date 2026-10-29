package com.czx.qqmessageassistantjava.dto;

import com.czx.qqmessageassistantjava.common.FlexibleLocalDateTimeDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "备忘录更新请求")
public class MemoPatchRequest {

    @Schema(description = "标题")
    private String title;

    @Schema(description = "内容")
    private String content;

    @Schema(description = "重要性")
    private Integer importance;

    @Schema(description = "类别：学业/社交/健康/财务/日常/其他")
    private String category;

    @Schema(description = "截止时间")
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime dueTime;

    @Schema(description = "设为 true 表示清空截止时间（并撤销未发提醒）")
    private Boolean clearDueTime;

    @Schema(description = "状态")
    private String status;

}
