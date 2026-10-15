package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.DraftCreateRequest;
import com.czx.qqmessageassistantjava.entity.Draft;
import com.czx.qqmessageassistantjava.service.DraftService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/drafts")
public class DraftController {

    @Autowired
    private DraftService draftService;

    @PostMapping
    public Result<Draft> createDraft(@RequestBody DraftCreateRequest draftCreateRequest){
        return Result.ok(draftService.createDraft(draftCreateRequest));
    }

    @GetMapping
    public Result<List<Draft>> getDraftList(@RequestParam(required = false) String type,
                                           @RequestParam(required = false) String status){
        return Result.ok(draftService.getDraftList(type, status));
    }

    @PostMapping("/{id}/confirm")
    public Result<Draft> confirmDraft(@PathVariable Long id){
        return Result.ok(draftService.confirmDraft(id));
    }

    @PostMapping("/{id}/reject")
    public Result<Draft> rejectDraft(@PathVariable Long id){
        return Result.ok(draftService.rejectDraft(id));
    }

}
