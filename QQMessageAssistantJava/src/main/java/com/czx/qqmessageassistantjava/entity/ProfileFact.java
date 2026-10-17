package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("profile_fact")
public class ProfileFact {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long senderId;
    private String factKey;
    private String factValue;
    private BigDecimal confidence;
//    AUTO/MANUAL
    private String source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
