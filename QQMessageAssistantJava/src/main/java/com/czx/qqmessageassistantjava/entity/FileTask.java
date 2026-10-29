package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@TableName(value = "file_task", autoResultMap = true)
public class FileTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** LangBot 侧 taskId，幂等键 */
    private String taskRef;

    private Long senderId;

    private Long chatId;

    /** 触发任务的 Dify conversation_id */
    private String conversationId;

    private String fileName;

    /** 用户原始填写要求 */
    private String request;

    /** 等待文件时挂起的要求（WAIT_FILE 的 request） */
    private String pendingRequest;

    /** PENDING|PROCESSING|NEED_INPUT|AWAITING_CONFIRM|SENT|FAILED|CANCELLED */
    private String status;

    /** Dify 判定仍缺的字段 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> missing;

    /** 填写置信度 0-1 */
    private BigDecimal confidence;

    /** 是否强制人工确认 */
    private Boolean needsConfirm;

    /** 输入文件引用，如 local:///abs/input.xlsx */
    private String fileRef;

    /** 产物文件引用，如 local:///abs/out.xlsx */
    private String outputRef;

    /** 源 QQ 消息 id */
    private Long sourceMessageId;

    /** 重复投递去重键 */
    private String idempotencyKey;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> detail;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}