package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * QQ 会话与 Dify conversation_id 的映射。
 *
 * <p>私聊 chat_id 固定为 0（唯一键需要非空列）；群聊使用真实群号。
 * 一个用户在一个会话里只保留一条 ACTIVE 记录，reset 后同一条记录重新 bind。</p>
 */
@Data
@TableName("conversation_session")
public class ConversationSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String botUuid;

    /** private / group */
    private String chatType;

    /** 私聊为 0，群聊为真实群号 */
    private Long chatId;

    private Long senderId;

    private String difyConversationId;

    /** ACTIVE / RESET */
    private String status;

    private LocalDateTime lastActiveAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
