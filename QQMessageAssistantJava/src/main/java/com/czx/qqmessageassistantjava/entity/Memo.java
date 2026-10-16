package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("memo")
public class Memo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long senderId;

    private Long chatId;

    private String title;

    private String content;

    private Integer importance;

    private String category;

    private LocalDateTime dueTime;

    private String status;

    private Long sourceMsgId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer isDeleted;
}
