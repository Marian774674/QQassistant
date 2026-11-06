package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.NoiseFilterCheckRequest;
import com.czx.qqmessageassistantjava.service.MessageNoiseFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插件前置过滤的判定入口，顶替原来「拦截规则」在消息流程里的位置。
 *
 * <p>判定放在 Spring 而不是插件本地缓存，是有意为之：噪音规则里两条是正则，
 * Java 与 Python 的正则方言并不完全相同（{@code \p{P}} 之类），两套实现迟早会
 * 出现「网页试跑说保留、插件却把消息丢了」。这里让入口、整理、夜间归纳共用
 * 同一个 {@link MessageNoiseFilter}，规则改完立刻生效，也不需要定时热更新。
 *
 * <p>走 {@code /api/agent/**}，由 {@code X-Agent-Token} 鉴权；插件拿不到配置或
 * Spring 不可用时按「非噪音」放行，绝不因为过滤把消息吞掉。
 */
@Tag(name = "插件：噪音过滤判定")
@RestController
@RequestMapping("/api/agent/noise-filter")
public class AgentNoiseFilterController {

    @Autowired
    private MessageNoiseFilter messageNoiseFilter;

    @Operation(summary = "判断单条消息是否为噪音（插件前置过滤）")
    @PostMapping("/check")
    public Result<Map<String, Object>> check(@RequestBody NoiseFilterCheckRequest request) {
        String reason = messageNoiseFilter.dropReasonForText(
                request == null ? null : request.getText(),
                request == null ? null : request.getMsgType());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("noise", reason != null);
        // Map.of 不接受 null；保留时用空串，插件只关心 noise 这个布尔
        data.put("reason", reason == null ? "" : reason);
        return Result.ok(data);
    }
}
