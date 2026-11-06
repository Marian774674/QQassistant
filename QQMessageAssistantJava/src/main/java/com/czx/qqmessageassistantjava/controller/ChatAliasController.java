package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.ChatAliasRequest;
import com.czx.qqmessageassistantjava.entity.ChatAlias;
import com.czx.qqmessageassistantjava.service.ChatAliasService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "会话称呼", description = "记忆页里查看 / 修改 / 删除群和私聊的展示名")
@RestController
@RequestMapping("/api/chat-aliases")
public class ChatAliasController {

    @Autowired
    private ChatAliasService chatAliasService;

    @Operation(summary = "全部会话称呼")
    @GetMapping
    public Result<List<ChatAlias>> list() {
        return Result.ok(chatAliasService.listAllAliases());
    }

    @Operation(summary = "修改会话称呼", description = "逗号分隔；第一个用于展示")
    @PutMapping
    public Result<Void> save(@RequestBody ChatAliasRequest request) {
        chatAliasService.saveAliases(request.getChatId(), request.getKind(), request.getAliases());
        return Result.ok();
    }

    @Operation(summary = "删除会话称呼")
    @DeleteMapping("/{chatId}")
    public Result<Void> delete(@PathVariable Long chatId, @RequestParam String kind) {
        chatAliasService.deleteAliases(chatId, kind);
        return Result.ok();
    }
}
