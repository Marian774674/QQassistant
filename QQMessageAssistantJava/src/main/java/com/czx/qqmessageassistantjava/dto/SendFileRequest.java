package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "LangBot 主动发文件请求（与发文本是两套契约）")
public class SendFileRequest {

    private SendTarget target;

    @Schema(description = "文件引用：LangBot 本地路径或任务引用")
    private String fileRef;

    @Schema(description = "展示文件名")
    private String fileName;

    @Schema(description = "业务引用")
    private String bizRef;
}
