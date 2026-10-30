package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("memory_note")
public class MemoryNote {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 主体；空=关于主人自己 */
    private Long subjectSenderId;

    private String predicate;

    private String value;

    private LocalDateTime happenedAt;

    /** message_log.id，逗号分隔；每一条都必须能回查 */
    private String sourceMsgIds;

    private BigDecimal confidence;

    private LocalDateTime createdAt;
}
