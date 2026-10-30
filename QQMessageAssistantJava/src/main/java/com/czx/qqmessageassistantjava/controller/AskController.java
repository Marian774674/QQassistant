package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.AskRequest;
import com.czx.qqmessageassistantjava.service.AskService;
import com.czx.qqmessageassistantjava.vo.AskResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "历史查询")
@RestController
@RequestMapping("/api/ask")
public class AskController {

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @Operation(summary = "一句话查回去；认不出人时返回 needClarify，回答后带上 clarify 重发原问题")
    @PostMapping
    public Result<AskResponse> ask(@RequestBody AskRequest request) {
        if (request == null) {
            throw new BusinessException("请求不能为空");
        }
        return Result.ok(askService.ask(request));
    }
}
