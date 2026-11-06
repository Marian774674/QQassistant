package com.czx.qqmessageassistantjava.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 补拉目标：插件拿到这张表，才知道「该去哪些会话翻历史、翻到哪一条为止」。
 *
 * <p>只给目标和游标，不给消息——补拉本身是插件直连 NapCat 做的，
 * Spring 不碰 QQ 协议（红线）。
 */
@Data
@Schema(description = "NapCat 补拉目标")
public class NapcatTargetVO {

    @Schema(description = "GROUP | PRIVATE")
    private String chatType;

    @Schema(description = "群号；私聊为对方 QQ")
    private Long chatId;

    @Schema(description = "群名；私聊为空（补写日志时原样带上，读起来才知道是哪个群）")
    private String chatName;

    @Schema(description = "已补到的 message_seq；0 表示从最新一页开始")
    private Long lastMessageSeq;
}
