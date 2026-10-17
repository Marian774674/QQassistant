package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
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

    private String fileName;

    /** PENDING|PROCESSING|NEED_INPUT|AWAITING_CONFIRM|SENT|FAILED|CANCELLED */
    private String status;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> detail;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}