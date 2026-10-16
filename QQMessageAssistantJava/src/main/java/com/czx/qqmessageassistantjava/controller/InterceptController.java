package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.InterceptRuleRequest;
import com.czx.qqmessageassistantjava.entity.InterceptRule;
import com.czx.qqmessageassistantjava.service.InterceptRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "拦截规则管理")
@RestController
@RequestMapping("/api/intercept-rules")
public class InterceptController {

    @Autowired
    private InterceptRuleService interceptRuleService;

    @Operation(summary = "获取所有拦截规则")
    @GetMapping
    public Result<List<InterceptRule>> listAllRule(){
        return Result.ok(interceptRuleService.listAllRule());
    }

    @Operation(summary = "创建拦截规则")
    @PostMapping
    public Result<InterceptRule> createRule(@RequestBody InterceptRuleRequest request){
        return Result.ok(interceptRuleService.createRule(request));
    }

    @Operation(summary = "更新拦截规则")
    @PutMapping("/{id}")
    public Result<InterceptRule> updateRule(@PathVariable Long id,@RequestBody InterceptRuleRequest request){
        return Result.ok(interceptRuleService.updateRule(id,request));
    }

    @Operation(summary = "删除拦截规则")
    @DeleteMapping("/{id}")
    public Result<Void> deleteRule(@PathVariable Long id){
        interceptRuleService.deleteRule(id);
        return Result.ok();
    }
}
