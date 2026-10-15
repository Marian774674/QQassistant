package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "draft", autoResultMap = true)
public class Draft {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableLogic
    private Integer isDeleted;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String,Object> payload;

    private String type;
    private Long senderId;
    private Long chatId;
    private String title;
    private String status;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;



}
