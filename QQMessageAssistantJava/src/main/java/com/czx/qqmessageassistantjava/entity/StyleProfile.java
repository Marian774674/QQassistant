package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("style_profile")
public class StyleProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long senderId;

    /** 注入 Dify 的画像正文 */
    private String profilePrompt;

    private LocalDateTime lastSummarizedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
