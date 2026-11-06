package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.CheckResult;
import com.czx.qqmessageassistantjava.dto.CollectionTaskRequest;
import com.czx.qqmessageassistantjava.service.CollectionTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务防重状态。
 *
 * <p>原来的 {@code GET /rules}（给插件拉拦截规则）已随拦截规则一起下线，
 * 插件的前置过滤改走 {@code /api/agent/noise-filter/check}。
 * 这两个 check/mark 端点保留兼容：{@code collection_task} 表里已有数据不动，
 * 目前没有调用方，但删端点无收益。
 */
@Tag(name = "任务防重")
@RestController
@RequestMapping("/api/collection-tasks")
public class CollectionTaskController {

    @Autowired
    private CollectionTaskService collectionTaskService;

    @Operation(summary = "检查任务是否已执行")
    @PostMapping("/check")
    public Result<CheckResult> check(@RequestBody CollectionTaskRequest request){
        return Result.ok(new CheckResult(collectionTaskService.checkReplied(request)));
    }

    @Operation(summary = "标记任务已执行")
    @PostMapping("/mark")
    public Result<Boolean> mark(@RequestBody CollectionTaskRequest request){
        return Result.ok(collectionTaskService.markReplied(request));
    }
}
