package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 每群每小时的整理结果；{chat_id, period_start} 唯一。 */
@Data
@TableName("chat_digest_item")
public class ChatDigestItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long chatId;

    private String chatName;

    private LocalDateTime periodStart;

    private LocalDateTime periodEnd;

    private String summary;

    /** 1-5，越大越该让用户看到 */
    private Integer importance;

    private String keywords;

    private Boolean mentionsMe;

    /** message_log.id，逗号分隔 */
    private String sourceMsgIds;

    private LocalDateTime createdAt;
}
