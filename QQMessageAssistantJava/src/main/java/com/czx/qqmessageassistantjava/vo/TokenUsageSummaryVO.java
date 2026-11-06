package com.czx.qqmessageassistantjava.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** 只读账本的时间窗汇总。 */
@Data
@Schema(description = "Token 用量汇总")
public class TokenUsageSummaryVO {

    private int days;
    private String since;
    private long calls;
    private long promptTokens;
    private long completionTokens;
    private long totalTokens;
    private List<Map<String, Object>> byTask;
    private List<Map<String, Object>> bySource;
    private List<Map<String, Object>> byDay;
    /** 「花在哪个会话」饼图；Top N + 其它 + 未归属，已在服务端合并好 */
    private List<TokenUsageChatVO> byChat;
}
