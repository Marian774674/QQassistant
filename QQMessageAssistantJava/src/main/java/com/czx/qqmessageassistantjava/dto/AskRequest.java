package com.czx.qqmessageassistantjava.dto;

import lombok.Data;

@Data
public class AskRequest {
    private String question;
    /** 上一轮反问的回答；为空表示这是第一轮 */
    private ClarifyAnswer clarify;
}
