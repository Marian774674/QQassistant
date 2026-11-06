package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次模型调用的 token 用量。只追加，不更新、不删除。 */
@Data
@TableName("token_usage")
public class TokenUsage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** spring = Dify Workflow（整理/归纳/问答）；plugin = 对话 Chatflow / 文件工作流 */
    private String source;

    private String task;

    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Long chatId;

    private LocalDateTime createdAt;
}
