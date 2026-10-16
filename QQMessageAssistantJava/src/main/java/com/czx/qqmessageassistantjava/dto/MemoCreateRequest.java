package com.czx.qqmessageassistantjava.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "备忘录创建请求")
public class MemoCreateRequest {

    @Schema(description = "发送者ID")
    private Long senderId;
    @Schema(description = "群聊ID")
    private Long chatId;
    @Schema(description = "标题")
    private String title;
    @Schema(description = "内容")
    private String content;
    @Schema(description = "重要性")
    private Integer importance;
    @Schema(description = "类别")
    private String category;
    @Schema(description = "截止时间")
    private LocalDateTime dueTime;
    @Schema(description = "源消息ID")
    private Long sourceMsgId;

}
