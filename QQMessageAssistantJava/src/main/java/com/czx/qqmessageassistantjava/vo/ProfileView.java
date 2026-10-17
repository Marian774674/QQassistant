package com.czx.qqmessageassistantjava.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话注入响应体")
public class ProfileView {

    @Schema(description = "事实记忆 kv")
    private Map<String, String> facts;

    @Schema(description = "风格画像正文")
    private String stylePrompt;
}
