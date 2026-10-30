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
    /** 发送者昵称；插件拿不到时为 null */
    private String senderName;
    private Long chatId;
    /** 群名；私聊为 null */
    private String chatName;
    /** Dify conversation_id，可用于按会话回溯 */
    private String conversationId;
    /** 平台消息 id（有则记录） */
    private String messageId;
    /** 幂等键：重复写入被忽略 */
    private String dedupeKey;
    private String direction;
    /** 仅 OUT 有意义：TASK（备忘/文件/反问）| CHAT（日常应答） */
    private String replyKind;
    private String msgType;
    private String content;
    private LocalDateTime createdAt;
}
