package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("intercept_rule")
public class InterceptRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String keywords;
    private String regex; // 正则表达式
    private String replyTemplate; // 回复模板
    private Integer priority; // 优先级
    private Boolean enabled; // 是否启用
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
