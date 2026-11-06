package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "确认草稿请求；命名草稿用 aliases，回复草稿可用 content 改写正文")
public class DraftConfirmRequest {

    @Schema(description = "会话名字，逗号分隔；第一个用于展示", example = "小明,明明")
    private String aliases;

    /**
     * 改写后的回复正文。
     * <p>只有 {@code REPLY} 草稿认这个字段；不传（null）表示按模型原本写的发出去，
     * 传空串视为「想发一条空消息」，会被拒绝——那多半是误删了内容。
     */
    @Schema(description = "改写后的回复正文；仅 REPLY 草稿生效，不传则按原正文发送",
            example = "好呀，你想去哪吃？")
    private String content;
}
