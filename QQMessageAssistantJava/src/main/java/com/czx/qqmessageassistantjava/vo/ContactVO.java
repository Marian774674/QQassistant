package com.czx.qqmessageassistantjava.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ContactVO {
    private Long id;
    private Long senderId;
    private String alias;
    private String relation;
    private Integer importance;
    private String note;
    private String source;
    private LocalDateTime updatedAt;
}
