package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.StyleProfileUpdateRequest;
import com.czx.qqmessageassistantjava.entity.StyleProfile;
import com.czx.qqmessageassistantjava.service.StyleProfileService;
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

    @Operation(summary = "查看当前画像·风格")
    @RequestMapping("/{senderId}")
    public Result<StyleProfile> getStyleProfile(@PathVariable Long senderId) {
        return Result.ok(styleProfileService.getBySenderId(senderId));
    }

    @Operation(summary = "写回画像", description = "upsert 覆盖，并置 lastSummarizedAt=now")
    @PostMapping("/update")
    public Result<StyleProfile> update(@RequestBody StyleProfileUpdateRequest request) {
        return Result.ok(styleProfileService.upsert(request.getSenderId(), request.getProfilePrompt()));
    }

}
