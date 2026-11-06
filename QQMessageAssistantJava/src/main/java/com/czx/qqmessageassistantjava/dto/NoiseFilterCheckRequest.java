package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 插件前置过滤的单条判定输入。
 *
 * <p>只传原文和消息类型，不传规则：规则始终以 Spring 的 {@code app_setting} 为准，
 * 避免插件缓存与网页设置各持一份、改了不生效。
 */
@Data
@Schema(description = "单条消息噪音判定输入")
public class NoiseFilterCheckRequest {

    @Schema(description = "消息正文")
    private String text;

    @Schema(description = "消息类型，text / file；非文本一律保留")
    private String msgType;
}
