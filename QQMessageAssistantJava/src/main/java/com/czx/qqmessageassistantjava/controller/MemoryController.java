package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.service.MemoryJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "记忆归纳")
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryJobService memoryJobService;

    public MemoryController(MemoryJobService memoryJobService) {
        this.memoryJobService = memoryJobService;
    }

    @Operation(summary = "从头补算全部历史消息（首次启用时手工跑一次）")
    @PostMapping("/rebuild")
    public Result<Integer> rebuild() {
        return Result.ok(memoryJobService.rebuild());
    }
}
