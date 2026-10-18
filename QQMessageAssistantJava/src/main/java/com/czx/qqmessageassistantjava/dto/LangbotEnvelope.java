package com.czx.qqmessageassistantjava.dto;

import lombok.Data;

@Data
public class LangbotEnvelope<T> {

    private int code;
    private String message;
    private T data;

}
