package com.czx.qqmessageassistantjava.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 前端「今天发生了什么」列表项。 */
@Data
@Schema(description = "消息整理结果")
public class DigestItemVO {

    @Schema(description = "整理结果 id")
    private Long id;
    @Schema(description = "群号")
    private Long chatId;
    @Schema(description = "群名")
    private String chatName;
    @Schema(description = "一句话摘要")
    private String summary;
    @Schema(description = "1-5，越大越该先看")
    private Integer importance;
    @Schema(description = "是否有人 @ 你")
    private Boolean mentionsMe;
    @Schema(description = "原文证据：message_log.id，逗号分隔")
    private String sourceMsgIds;
    @Schema(description = "这条整理覆盖的时间窗结束时间")
    private LocalDateTime periodEnd;
    @Schema(description = "入库时间")
    private LocalDateTime createdAt;
}
