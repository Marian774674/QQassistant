package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("memo_reminder")
public class MemoReminder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memoId;

    private String offsetLabel;

    private LocalDateTime remindAt;

    private String status;

    private LocalDateTime sentAt;

    private LocalDateTime createdAt;
}
