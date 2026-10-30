package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.ChatSettingRequest;
import com.czx.qqmessageassistantjava.entity.ChatSetting;
import com.czx.qqmessageassistantjava.service.ChatSettingService;
import com.czx.qqmessageassistantjava.vo.ReplyContextVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "会话开关")
@RestController
@RequestMapping("/api/chat-settings")
public class ChatSettingController {

    @Autowired
    private ChatSettingService chatSettingService;

    @Operation(summary = "全部会话开关")
    @GetMapping
    public Result<List<ChatSetting>> list() {
        return Result.ok(chatSettingService.listAll());
    }

    @Operation(summary = "新建或更新一个会话的开关（两个开关不传即不改）")
    @PutMapping
    public Result<ChatSetting> upsert(@RequestBody ChatSettingRequest request) {
        return Result.ok(chatSettingService.upsert(
                request.getChatId(), request.getKind(), request.getChatName(),
                request.getDigestEnabled(), request.getAutoReplyEnabled()));
    }

    @Operation(summary = "自动回复的决策快照（LangBot 调 Dify 之前取一次）")
    @GetMapping("/reply-context")
    public Result<ReplyContextVO> replyContext(@RequestParam Long chatId,
                                               @RequestParam(required = false) String kind,
                                               @RequestParam(required = false) Integer limit) {
        return Result.ok(chatSettingService.replyContext(chatId, kind, limit));
    }
}
