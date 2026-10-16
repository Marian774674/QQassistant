package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.entity.InterceptRule;
import com.czx.qqmessageassistantjava.service.InterceptRuleService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/collection-tasks")
public class CollectionTaskController {

    @Autowired
    private InterceptRuleService interceptRuleService;

    @GetMapping("/rules")
    public Result<List<InterceptRule>> listAllEnableRule(){
        return Result.ok(interceptRuleService.listEnabledRule());
    }

}
