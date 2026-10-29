package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 备忘的变更留痕。
 *
 * 为什么需要它：AI 通过 {@code POST /api/agent/ops} 直接改库，这条写路径不经过插件的
 * message_log。没有这张表，「谁在什么时候把班会从 S109 改成 S111」就查不到了。
 *
 * {@code eventId} 同时是幂等键（唯一索引）：同一条 QQ 消息被 Dify 重试或重复投递时，
 * 只落一次业务写入。人工在 Vue 里改的行没有 eventId（NULL），不参与去重 ——
 * MySQL 唯一索引允许多个 NULL。
 */
@Data
@TableName("memo_change_log")
public class MemoChangeLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memoId;

    private Long senderId;

    /** AGENT | VUE */
    private String source;

    /** CREATE | UPDATE | CLOSE */
    private String op;

    private String eventId;

    private String beforeJson;

    private String afterJson;

    private String resultJson;

    private String conversationId;

    private LocalDateTime createdAt;
}
