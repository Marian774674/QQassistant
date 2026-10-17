package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "设置")
@RestController
@RequestMapping("/api/settings")
public class AppSettingController {

    @Autowired
    private AppSettingService appSettingService;

    @Operation(summary = "读取全部配置")
    @GetMapping
    public Result<Map<String, String>> getAll() {
        return Result.ok(appSettingService.getAll());
    }

    @Operation(summary = "更新配置（部分更新）")
    @PutMapping
    public Result<Integer> putAll(@RequestBody Map<String, String> kv) {
        return Result.ok(appSettingService.putAll(kv));
    }
}