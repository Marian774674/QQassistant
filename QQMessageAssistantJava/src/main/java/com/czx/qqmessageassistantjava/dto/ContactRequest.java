package com.czx.qqmessageassistantjava.dto;

import lombok.Data;

@Data
public class ContactRequest {
    private Long senderId;
    private String alias;
    private String relation;
}
