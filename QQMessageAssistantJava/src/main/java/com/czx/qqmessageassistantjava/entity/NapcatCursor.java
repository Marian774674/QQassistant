package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * NapCat 补拉游标：这个会话已经补到哪一条。
 *
 * <p>存的是 NapCat 的 {@code message_seq} 而不是时间戳——同一秒里可能有多条消息，
 * 时间戳既不能排序也不能做「大于游标」的截断，补拉会把整页重复写一遍。
 *
 * <p>私聊没有群号，{@code chatId} 存对方 QQ，{@code chatType='PRIVATE'}；
 * 与 {@code chat_setting} / {@code chat_alias} 的约定一致。
 */
@Data
@TableName("napcat_cursor")
public class NapcatCursor {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** GROUP | PRIVATE */
    private String chatType;

    /** 群号；私聊存对方 QQ */
    private Long chatId;

    /** 已补到的最新 message_seq；只前进不后退 */
    private Long lastMessageSeq;

    private LocalDateTime updatedAt;
}
