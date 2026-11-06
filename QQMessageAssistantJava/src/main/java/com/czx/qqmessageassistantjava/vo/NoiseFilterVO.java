package com.czx.qqmessageassistantjava.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 当前生效值 + 出厂值（前端「恢复默认」用）。 */
@Data
@Schema(description = "噪音过滤配置")
public class NoiseFilterVO {

    private boolean enabled;
    private String keywords;
    private String regex;
    private String keepRegex;

    private String defaultKeywords;
    private String defaultRegex;
    private String defaultKeepRegex;
}
