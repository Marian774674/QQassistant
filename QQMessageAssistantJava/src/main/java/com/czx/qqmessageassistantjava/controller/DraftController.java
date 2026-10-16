package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.DraftCreateRequest;
import com.czx.qqmessageassistantjava.entity.Draft;
import com.czx.qqmessageassistantjava.service.DraftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "草稿管理", description = "统一草稿：创建/列表/确认/拒绝")
@RestController
@RequestMapping("/api/drafts")
public class DraftController {

    @Autowired
    private DraftService draftService;

    @Operation(summary = "创建草稿")
    @PostMapping
    public Result<Draft> createDraft(@RequestBody DraftCreateRequest draftCreateRequest){
        return Result.ok(draftService.createDraft(draftCreateRequest));
    }

    @Operation(summary = "草稿列表", description = "按状态/类型筛选，createdAt 倒序")
    @GetMapping
    public Result<List<Draft>> getDraftList(@RequestParam(required = false) String type,
                                           @RequestParam(required = false) String status){
        return Result.ok(draftService.getDraftList(type, status));
    }

    @Operation(summary = "确认草稿", description = "PENDING → CONFIRMED，按 type 分发")
    @PostMapping("/{id}/confirm")
    public Result<Draft> confirmDraft(@PathVariable Long id){
        return Result.ok(draftService.confirmDraft(id));
    }
    @Operation(summary = "拒绝草稿", description = "PENDING → REJECTED")
    @PostMapping("/{id}/reject")
    public Result<Draft> rejectDraft(@PathVariable Long id){
        return Result.ok(draftService.rejectDraft(id));
    }

}
