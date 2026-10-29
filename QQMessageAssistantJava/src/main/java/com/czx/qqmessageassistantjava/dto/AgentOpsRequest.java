package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Dify HTTP 节点发来的写操作请求。
 *
 * 为什么全是 String：Dify 的 JSON Body 只允许一个条目、且变量替换出来都是文本，
 * 强类型会在「importance 传成 "3"」「dueTime 传成 "2026-10-29 19:00"」这类小事上 400。
 * 统一在服务里显式解析，错误信息带字段名。
 *
 * 空串 = 未提供（不是「改成空」）。要清空 dueTime 用 {@code clearDueTime="true"}。
 *
 * senderId / chatId / eventId 必须由插件在调用 Dify 前填进 Start，模型无法篡改；
 * 服务端把它们当可信输入，因此这个端点必须带 token 且不能暴露到公网。
 */
@Data
public class AgentOpsRequest {

    @Schema(description = "幂等键，同一条消息重试时复用")
    private String eventId;

    @Schema(description = "memo.create | memo.update | memo.close")
    private String op;

    @Schema(description = "归属用户")
    private String senderId;

    @Schema(description = "群聊 id，私聊留空")
    private String chatId;

    @Schema(description = "要改的备忘 id（update/close 必填；close 可空表示最新一条待办）")
    private String memoId;

    private String title;

    private String content;

    private String importance;

    private String category;

    private String dueTime;

    @Schema(description = "\"true\" 表示清空 dueTime")
    private String clearDueTime;

    @Schema(description = "close 用：DONE | CANCELLED")
    private String status;

    private String conversationId;

    @Schema(description = "原始消息，仅用于留痕")
    private String query;
}
