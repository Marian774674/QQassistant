package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "消息日志项")
public class MessageLogItem {

    @Schema(description = "发送者ID")
    private Long senderId;
    @Schema(description = "发送者昵称；拿不到可省")
    private String senderName;
    @Schema(description = "聊天ID")
    private Long chatId;
    @Schema(description = "群名；私聊可省")
    private String chatName;
    @Schema(description = "Dify conversation_id")
    private String conversationId;
    @Schema(description = "平台消息 id")
    private String messageId;
    @Schema(description = "幂等键；相同值重复写入被忽略")
    private String dedupeKey;
    @Schema(description = "内容")
    private String content;
    @Schema(description = "方向",allowableValues = {"IN","OUT"})
    private String direction;
    @Schema(description = "消息类型")
    private String msgType;
    @Schema(description = "仅 OUT：TASK|CHAT", allowableValues = {"TASK", "CHAT"})
    private String replyKind;
    /**
     * 消息发生时刻（Unix 秒）。只有补拉会传：补回来的消息必须落在它原本的时间点上，
     * 否则整理的时间窗、夜间归纳的批次都会把它当成「刚刚发生」。
     *
     * <p>用 epoch 而不是 ISO 字符串：插件跑在容器里，容器时区未必是 Asia/Shanghai，
     * 传墙钟时间会整体偏 8 小时；epoch 由后端按自己的时区换算，和实时日志同一个轴。
     * 不传则退化为「写入时刻」。
     */
    @Schema(description = "消息发生时刻（Unix 秒）；补拉专用，不传按写入时刻")
    private Long createdAtEpochSeconds;
}
