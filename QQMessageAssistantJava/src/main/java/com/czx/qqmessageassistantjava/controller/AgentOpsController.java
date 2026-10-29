package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.AgentOpsRequest;
import com.czx.qqmessageassistantjava.service.AgentOpService;
import com.czx.qqmessageassistantjava.vo.AgentOpsResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Agent 写操作", description = "Dify HTTP 节点直连后端；必须带 X-Agent-Token")
@RestController
@RequestMapping("/api/agent")
public class AgentOpsController {

    @Autowired
    private AgentOpService agentOpService;

    /**
     * 业务拒绝（校验/归属/找不到）返回 HTTP 200 + code!=0：
     * Dify 的 HTTP 节点只在非 2xx 时走 fail-branch，如果这里也回 400，
     * "备忘不存在" 就会被说成 "没连上后台"。
     */
    @Operation(summary = "执行一次备忘写操作")
    @PostMapping("/ops")
    public ResponseEntity<Result<AgentOpsResult>> ops(@RequestBody AgentOpsRequest request) {
        try {
            AgentOpsResult result = agentOpService.execute(request);
            return ResponseEntity.ok(Result.ok(result.getMessage(), result));
        } catch (BusinessException e) {
            return ResponseEntity.ok(Result.fail(400, e.getMessage()));
        }
    }
}
