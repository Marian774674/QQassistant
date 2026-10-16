package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("collection_task")
public class CollectionTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long chatId;

    private String taskType;

    private String taskKey;

    private LocalDateTime windowStart;

    private String status;

    private LocalDateTime repliedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
