package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 每个会话的开关。没这行 = 两个功能都关。
 *
 * <p>私聊没有群号，{@code chatId} 存对方的 {@code sender_id}，{@code kind='PRIVATE'}；
 * 这样 {@code uk_chat(chat_id, kind)} 对私聊同样有效（NULL 不参与唯一约束，存 NULL 会重复建行）。
 */
@Data
@TableName("chat_setting")
public class ChatSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 群号；私聊存对方 sender_id */
    private Long chatId;
    /** GROUP | PRIVATE */
    private String kind;
    private String chatName;
    /** 日常对话自动回复；默认关 */
    private Boolean autoReplyEnabled;
    /** 是否纳入消息整理；默认关 */
    private Boolean digestEnabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
