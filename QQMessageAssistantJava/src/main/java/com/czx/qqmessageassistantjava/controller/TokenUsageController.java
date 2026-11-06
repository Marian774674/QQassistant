package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.TokenUsageRecordRequest;
import com.czx.qqmessageassistantjava.entity.TokenUsage;
import com.czx.qqmessageassistantjava.service.TokenUsageService;
import com.czx.qqmessageassistantjava.vo.TokenUsageSummaryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Token 用量账本：插件经 {@code /api/agent/**} 写入，Vue 只读汇总与明细。
 *
 * <p>写入口和读取口分开：插件没有浏览器会话，只能带共享 token；
 * 网页侧要登录，且没有任何写/删接口——账本只追加，避免「看着不顺眼改数字」。
 */
@Tag(name = "Token 用量", description = "一次模型调用一行；网页只读，插件经 agent 通道写入")
@RestController
public class TokenUsageController {

    private final TokenUsageService service;

    public TokenUsageController(TokenUsageService service) {
        this.service = service;
    }

    @Operation(summary = "插件上报一次 Dify 调用的用量")
    @PostMapping("/api/agent/token-usage")
    public ResponseEntity<Result<Long>> record(@RequestBody TokenUsageRecordRequest request) {
        try {
            return ResponseEntity.ok(Result.ok(service.record(request).getId()));
        } catch (BusinessException e) {
            // 插件的 HTTP 客户端只在非 2xx 时抛错；业务错误也走 200 + code
            return ResponseEntity.ok(Result.fail(400, e.getMessage()));
        }
    }

    @Operation(summary = "时间窗汇总（默认近 7 天，最多 90 天）")
    @GetMapping("/api/token-usage/summary")
    public Result<TokenUsageSummaryVO> summary(
            @RequestParam(required = false, defaultValue = "7") int days) {
        return Result.ok(service.summary(days));
    }

    @Operation(summary = "最近调用明细（默认近 7 天 / 50 条）")
    @GetMapping("/api/token-usage/recent")
    public Result<List<TokenUsage>> recent(
            @RequestParam(required = false, defaultValue = "7") int days,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return Result.ok(service.recent(days, limit));
    }
}
