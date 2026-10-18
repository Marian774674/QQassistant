package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "发送目标")
public class SendTarget {

    @Schema(description = "private | group")
    private String type;

    @Schema(description = "私聊为 senderId，群聊为 groupId")
    private Long id;
}
