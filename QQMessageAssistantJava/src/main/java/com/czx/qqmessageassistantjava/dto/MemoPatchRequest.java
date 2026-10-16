package com.czx.qqmessageassistantjava.dto;

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

    @Schema(description = "截止时间")
    private LocalDateTime dueTime;

    @Schema(description = "状态")
    private String status;

}
