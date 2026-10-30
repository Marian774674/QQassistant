package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 会话开关的部分更新请求。
 *
 * <p>两个开关是 {@code Boolean} 而不是 {@code boolean}：{@code null} 表示「不改这一项」，
 * 前端只翻转一个开关时不必把另一个也传上来再覆盖一遍。
 */
@Data
@Schema(description = "会话开关")
public class ChatSettingRequest {

    @Schema(description = "群号；私聊填对方 QQ 号")
    private Long chatId;
    @Schema(description = "GROUP | PRIVATE", allowableValues = {"GROUP", "PRIVATE"})
    private String kind;
    @Schema(description = "群名；可空")
    private String chatName;
    @Schema(description = "日常对话自动回复；不传表示不改")
    private Boolean autoReplyEnabled;
    @Schema(description = "是否纳入消息整理；不传表示不改")
    private Boolean digestEnabled;
}
