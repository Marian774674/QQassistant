package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.FactManualRequest;
import com.czx.qqmessageassistantjava.dto.FactUpdateRequest;
import com.czx.qqmessageassistantjava.dto.FactUpsertRequest;
import com.czx.qqmessageassistantjava.entity.ProfileFact;
import com.czx.qqmessageassistantjava.service.ProfileFactService;
import com.czx.qqmessageassistantjava.service.StyleProfileService;
import com.czx.qqmessageassistantjava.vo.ProfileView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "画像·事实")
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    @Autowired
    private ProfileFactService profileFactService;
    @Autowired
    private StyleProfileService styleProfileService;

    @Operation(summary = "会话注入", description = "Dify 会话开始时拉取：事实 kv + 风格画像")
    @GetMapping("/{senderId}")
    public Result<ProfileView> inject(@PathVariable Long senderId){
        return Result.ok(new ProfileView(
                profileFactService.factMap(senderId),
                styleProfileService.promptOf(senderId)
        ));
    }

    @Operation(summary = "高置信事实写入", description = "upsert 覆盖，source=AUTO")
    @PutMapping("/facts")
    public Result<ProfileFact> updateFact(@RequestBody FactUpsertRequest request){
        return Result.ok(profileFactService.upsertAuto(request));
    }

    @Operation(summary = "事实列表")
    @GetMapping("/facts")
    public Result<List<ProfileFact>> listFacts(@RequestParam(required = false) Long senderId){
        return Result.ok(profileFactService.listBySenderId(senderId));
    }

    @Operation(summary = "手动新增事实", description = "source=MANUAL")
    @PostMapping("/facts")
    public Result<ProfileFact> createFact(@RequestBody FactManualRequest request){
        return Result.ok(profileFactService.createManual(request));
    }

    @Operation(summary = "手动修改事实")
    @PutMapping("/facts/{id}")
    public Result<ProfileFact> updateFact(@PathVariable Long id, @RequestBody FactUpdateRequest request) {
        return Result.ok(profileFactService.updateFact(id, request));
    }

    @Operation(summary = "手动删除事实", description = "物理删除")
    @DeleteMapping("/facts/{id}")
    public Result<Void> deleteFact(@PathVariable Long id) {
        profileFactService.deleteFact(id);
        return Result.ok();
    }
}
