package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.ConversationBindRequest;
import com.czx.qqmessageassistantjava.dto.ConversationResolveResponse;
import com.czx.qqmessageassistantjava.entity.ConversationSession;
import com.czx.qqmessageassistantjava.service.ConversationSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "会话映射", description = "QQ 会话与 Dify conversation_id 的持久化映射")
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    @Autowired
    private ConversationSessionService conversationSessionService;

    @Operation(summary = "解析当前会话的 Dify conversation_id")
    @GetMapping("/resolve")
    public Result<ConversationResolveResponse> resolve(
            @RequestParam String botUuid,
            @RequestParam String chatType,
            @RequestParam(required = false) Long chatId,
            @RequestParam Long senderId) {
        return Result.ok(new ConversationResolveResponse(
                conversationSessionService.resolve(botUuid, chatType, chatId, senderId)));
    }

    @Operation(summary = "绑定/更新 Dify conversation_id")
    @PostMapping("/bind")
    public Result<ConversationSession> bind(@RequestBody ConversationBindRequest request) {
        return Result.ok(conversationSessionService.bind(request));
    }

    @Operation(summary = "重置会话", description = "标记 RESET；下次绑定会使用新的 conversation_id")
    @PostMapping("/reset")
    public Result<Void> reset(@RequestBody ConversationBindRequest request) {
        conversationSessionService.reset(request);
        return Result.ok();
    }
}
