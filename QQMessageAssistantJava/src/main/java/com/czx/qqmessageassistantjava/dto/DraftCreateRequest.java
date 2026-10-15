package com.czx.qqmessageassistantjava.dto;

import lombok.Data;

import java.util.Map;

@Data
public class DraftCreateRequest {
    private String type;
    private Long senderId;
    private Long chatId;
    private String title;
    private Map<String,Object> payload;
}
