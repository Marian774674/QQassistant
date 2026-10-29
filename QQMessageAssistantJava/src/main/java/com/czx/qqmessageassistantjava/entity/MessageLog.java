package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("message_log")
public class MessageLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long senderId;
    private Long chatId;
    /** Dify conversation_id，可用于按会话回溯 */
    private String conversationId;
    /** 平台消息 id（有则记录） */
    private String messageId;
    /** 幂等键：重复写入被忽略 */
    private String dedupeKey;
    private String direction;
    private String msgType;
    private String content;
    private LocalDateTime createdAt;
}
