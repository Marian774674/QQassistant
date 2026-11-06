package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.NapcatCursorRequest;
import com.czx.qqmessageassistantjava.service.NapcatCursorService;
import com.czx.qqmessageassistantjava.vo.NapcatTargetVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * NapCat 掉线补拉：插件从这里拿「该补哪些会话」和「补到哪了」。
 *
 * <p>补拉本身由插件直连 NapCat 完成——Spring 不碰 QQ 协议（红线）。
 * 这里只提供目标和游标，所以两个接口都走 {@code /api/agent/**} 的共享 token 通道，
 * 插件没有浏览器会话。
 */
@Tag(name = "NapCat 补拉", description = "插件经 X-Agent-Token 调用；只给目标和游标，不碰消息")
@RestController
@RequestMapping("/api/agent/napcat")
public class NapcatController {

    @Autowired
    private NapcatCursorService napcatCursorService;

    @Operation(summary = "待补拉会话 + 各自游标")
    @GetMapping("/targets")
    public Result<List<NapcatTargetVO>> targets(@RequestParam(required = false) Integer limit) {
        return Result.ok(napcatCursorService.targets(limit));
    }

    /**
     * 与 {@code /api/agent/ops} 同一套约定：业务拒绝回 HTTP 200 + code!=0，
     * 插件的 HTTP 客户端只在非 2xx 时抛错，混用会让「参数写错了」被当成「服务没起来」。
     */
    @Operation(summary = "推进补拉游标（只前进不后退）")
    @PostMapping("/cursor")
    public ResponseEntity<Result<Long>> advance(@RequestBody NapcatCursorRequest request) {
        try {
            long cursor = napcatCursorService.advance(
                    request.getChatType(), request.getChatId(), request.getLastMessageSeq());
            return ResponseEntity.ok(Result.ok(cursor));
        } catch (BusinessException e) {
            return ResponseEntity.ok(Result.fail(400, e.getMessage()));
        }
    }
}
