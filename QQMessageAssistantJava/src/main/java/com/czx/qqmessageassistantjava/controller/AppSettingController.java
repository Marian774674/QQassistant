package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.constant.AppSettingKeys;
import com.czx.qqmessageassistantjava.dto.NoiseFilterPreviewRequest;
import com.czx.qqmessageassistantjava.dto.NoiseFilterUpdateRequest;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import com.czx.qqmessageassistantjava.service.NoiseFilterSettingsService;
import com.czx.qqmessageassistantjava.vo.NoiseFilterVO;
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

    @Autowired
    private NoiseFilterSettingsService noiseFilterSettingsService;

    @Operation(summary = "读取全部配置")
    @GetMapping
    public Result<Map<String, String>> getAll() {
        return Result.ok(appSettingService.getAll());
    }

    /**
     * 只放行网页真正能改的键。
     *
     * <p>以前是「给什么写什么」：登录页的口令哈希 {@code auth_password_hash}、
     * 夜间归纳游标、整理锚点都在这张表里，任何一个带上浏览器会话的人都能顺手改掉
     * （等于绕过登录改密码）。系统自己维护的键走 {@code AppSettingService} 直写，
     * 不经过这个 HTTP 入口，所以白名单不影响它们。
     */
    @Operation(summary = "更新配置（部分更新，仅限可编辑键）")
    @PutMapping
    public Result<Integer> putAll(@RequestBody Map<String, String> kv) {
        if (kv != null) {
            for (String key : kv.keySet()) {
                if (key == null || !AppSettingKeys.USER_EDITABLE.contains(key)) {
                    throw new BusinessException("配置键不允许从网页修改：" + key);
                }
            }
        }
        return Result.ok(appSettingService.putAll(kv));
    }

    @Operation(summary = "读取噪音过滤配置（含出厂值）")
    @GetMapping("/noise-filter")
    public Result<NoiseFilterVO> noiseFilter() {
        return Result.ok(noiseFilterSettingsService.get());
    }

    @Operation(summary = "更新噪音过滤配置（正则会在保存前编译校验）")
    @PutMapping("/noise-filter")
    public Result<NoiseFilterVO> updateNoiseFilter(@RequestBody NoiseFilterUpdateRequest request) {
        return Result.ok(noiseFilterSettingsService.update(request));
    }

    @Operation(summary = "试跑噪音过滤：按请求里带的草稿规则逐句判断，不写库")
    @PostMapping("/noise-filter/preview")
    public Result<Map<String, Object>> previewNoiseFilter(
            @RequestBody NoiseFilterPreviewRequest request) {
        boolean enabled = request != null && request.getEnabled() != null
                ? request.getEnabled() : noiseFilterSettingsService.get().isEnabled();
        return Result.ok(Map.of(
                "enabled", enabled,
                "items", noiseFilterSettingsService.preview(
                        request == null ? null : request.getTexts(), request)));
    }
}
