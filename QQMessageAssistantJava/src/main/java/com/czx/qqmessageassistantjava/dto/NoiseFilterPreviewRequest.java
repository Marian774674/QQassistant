package com.czx.qqmessageassistantjava.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "噪音过滤试跑输入")
public class NoiseFilterPreviewRequest {

    @Schema(description = "要试的句子，最多 20 条、每条最多 200 字")
    private List<String> texts;

    /**
     * 以下四项是「编辑框里还没保存的规则」。全为 null 表示用库里已生效的；
     * 只要带了一项，试跑就按这份草稿来，让「改完先试再存」真的有意义。
     */
    @Schema(description = "草稿：总开关")
    private Boolean enabled;

    @Schema(description = "草稿：噪音词")
    private String keywords;

    @Schema(description = "草稿：噪音正则")
    private String regex;

    @Schema(description = "草稿：保留正则")
    private String keepRegex;

    public boolean hasDraft() {
        return enabled != null || keywords != null || regex != null || keepRegex != null;
    }
}
