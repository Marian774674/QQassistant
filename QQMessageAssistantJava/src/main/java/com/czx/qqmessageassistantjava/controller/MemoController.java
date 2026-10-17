package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.MemoCreateRequest;
import com.czx.qqmessageassistantjava.dto.MemoPatchRequest;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.service.MemoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "备忘录",description = "创建/列表/编辑/完成/取消")
@RestController
@RequestMapping("/api/memos")
public class MemoController {

    @Autowired
    private MemoService memoService;


    @Operation(summary = "创建备忘", description = "按 importance 展开提醒计划")
    @PostMapping
    public Result<Memo> create(@RequestBody MemoCreateRequest request){
        return Result.ok(memoService.create(request));
    }
    @Operation(summary = "获取备忘列表")
    @GetMapping
    public Result<List<Memo>> list(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) Long senderId) {
        return Result.ok(memoService.list(status, senderId));
    }
    @Operation(summary = "编辑/完成/取消", description = "改 importance/dueTime 会重算提醒；DONE/CANCELLED 会撤销未发提醒")
    @PatchMapping("/{id}")
    public Result<Memo> patch(@PathVariable Long id,@RequestBody MemoPatchRequest request){
        return Result.ok(memoService.patch(id,request));
    }

}
