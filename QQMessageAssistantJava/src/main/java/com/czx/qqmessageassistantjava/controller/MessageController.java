package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.MessageLogItem;
import com.czx.qqmessageassistantjava.service.MessageLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "消息日志管理")
@RestController
@RequestMapping("/api/message-logs")
public class MessageController {

    @Autowired
    private MessageLogService messageService;

    @Operation(summary = "批量创建消息日志")
    @PostMapping
    public Result<Integer> batchCreate(@RequestBody List<MessageLogItem> items){
        return Result.ok(messageService.batchSave(items));
    }

}
