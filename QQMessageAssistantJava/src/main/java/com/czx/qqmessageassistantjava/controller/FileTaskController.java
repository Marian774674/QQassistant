package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.FileTaskSyncRequest;
import com.czx.qqmessageassistantjava.entity.FileTask;
import com.czx.qqmessageassistantjava.service.FileTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "文件任务镜像", description = "状态机真身在 LangBot 插件内，本表仅只读镜像供 Vue 展示")
@RestController
@RequestMapping("/api/file-tasks")
public class FileTaskController {

    @Autowired
    private FileTaskService fileTaskService;

    @Operation(summary = "只读任务列表")
    @GetMapping
    public Result<List<FileTask>> list(@RequestParam(required = false) Long senderId,
                                       @RequestParam(required = false) String status) {
        return Result.ok(fileTaskService.list(senderId, status));
    }

    @Operation(summary = "状态镜像同步", description = "按 taskRef upsert；只写不回读业务逻辑")
    @PostMapping("/sync")
    public Result<FileTask> sync(@RequestBody FileTaskSyncRequest request) {
        return Result.ok(fileTaskService.sync(request));
    }
}