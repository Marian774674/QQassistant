package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.StyleProfileUpdateRequest;
import com.czx.qqmessageassistantjava.entity.StyleProfile;
import com.czx.qqmessageassistantjava.service.StyleProfileService;
import com.czx.qqmessageassistantjava.service.StyleSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "画像·风格")
@RestController
@RequestMapping("/api/style-profile")
public class StyleProfileController {

    @Autowired
    private StyleProfileService styleProfileService;
    @Autowired
    private StyleSummaryService styleSummaryService;

    @Operation(summary = "查看当前画像·风格")
    @GetMapping("/{senderId}")
    public Result<StyleProfile> getStyleProfile(@PathVariable Long senderId) {
        return Result.ok(styleProfileService.getBySenderId(senderId));
    }

    @Operation(summary = "写回画像", description = "upsert 覆盖，并置 lastSummarizedAt=now")
    @PostMapping("/update")
    public Result<StyleProfile> update(@RequestBody StyleProfileUpdateRequest request) {
        return Result.ok(styleProfileService.upsert(request.getSenderId(), request.getProfilePrompt()));
    }

    @Operation(summary = "立即总结一次风格画像",
            description = "手动触发，不等定时点；没有主人消息 / Dify 未配置时返回 false，旧画像不变")
    @PostMapping("/summarize")
    public Result<Boolean> summarize() {
        return Result.ok(styleSummaryService.summarize());
    }

}
