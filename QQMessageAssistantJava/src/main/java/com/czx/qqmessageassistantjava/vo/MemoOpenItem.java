package com.czx.qqmessageassistantjava.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 喂给 Dify 的「未完成备忘」摘要。
 *
 * 只放模型挑 id 需要的字段：太大就烧 token，太多就选错。
 * 完整内容请走 {@code GET /api/memos/{id}}。
 */
@Data
public class MemoOpenItem {

    private Long id;

    private String title;

    @Schema(description = "已截断的详情摘要")
    private String content;

    private LocalDateTime dueTime;

    private String status;

    private Integer importance;

    private String category;
}
