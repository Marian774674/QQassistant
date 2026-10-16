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
    private String direction;
    private String msgType;
    private String content;
    private LocalDateTime createdAt;
}
