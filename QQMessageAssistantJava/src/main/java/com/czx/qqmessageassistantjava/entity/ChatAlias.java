package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一个会话（群聊或私聊）的展示名。
 *
 * <p>群号和某个人的 QQ 可能相同，所以身份是 {@code (chat_id, kind)}。
 * {@code aliases} 为空串表示用户明确拒绝命名，用于「以后不再追问」。
 */
@Data
@TableName("chat_alias")
public class ChatAlias {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long chatId;

    /** GROUP | PRIVATE */
    private String kind;

    /** 逗号分隔；第一个用于展示，全部保留给后续匹配 */
    private String aliases;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
