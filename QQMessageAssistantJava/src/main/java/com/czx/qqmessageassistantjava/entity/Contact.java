package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("contact")
public class Contact {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long senderId;

    private String alias;

    private String relation;

    private Integer importance;

    private String note;

    /** AUTO=夜间任务认出来的，USER=用户自己认过的；USER 永不被 AUTO 覆盖 */
    private String source;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
