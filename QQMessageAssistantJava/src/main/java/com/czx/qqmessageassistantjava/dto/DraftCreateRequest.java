package com.czx.qqmessageassistantjava.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.util.Map;

@Data
@Schema(description = "创建草稿请求")
public class DraftCreateRequest {

    @Schema(description = "草稿类型",allowableValues = {"REPLY", "FILE", "MEMO", "FACT"})
    private String type;

    @Schema(description = "归属用户")
    private Long senderId;

    @Schema(description = "会话")
    private Long chatId;

    @Schema(description = "列表展示标题")
    private String title;

    @Schema(description = "随 type 变化的内容体")
    private Map<String, Object> payload;
}