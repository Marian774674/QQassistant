package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.CheckResult;
import com.czx.qqmessageassistantjava.dto.CollectionTaskRequest;
import com.czx.qqmessageassistantjava.entity.CollectionTask;
import com.czx.qqmessageassistantjava.entity.InterceptRule;
import com.czx.qqmessageassistantjava.service.CollectionTaskService;
import com.czx.qqmessageassistantjava.service.InterceptRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "任务防重")
@RestController
@RequestMapping("/api/collection-tasks")
public class CollectionTaskController {

    @Autowired
    private InterceptRuleService interceptRuleService;

    @Autowired
    private CollectionTaskService collectionTaskService;

    @Operation(summary = "获取所有启用的拦截规则")
    @GetMapping("/rules")
    public Result<List<InterceptRule>> listAllEnableRule(){
        return Result.ok(interceptRuleService.listEnabledRule());
    }

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
