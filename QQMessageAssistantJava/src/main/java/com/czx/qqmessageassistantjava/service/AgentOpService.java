package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.dto.AgentOpsRequest;
import com.czx.qqmessageassistantjava.entity.MemoChangeLog;
import com.czx.qqmessageassistantjava.vo.AgentOpsResult;

import java.util.List;

/**
 * Dify 直连后端的窄写入口。
 *
 * 边界：这里只做「翻译 + 校验 + 委托」，真正的状态机、提醒重算仍在 {@link MemoService}。
 * 所有写入都留痕到 memo_change_log，并且用 eventId 去重。
 */
public interface AgentOpService {

    /** 执行一次写操作；同一 eventId 的重复请求直接返回上次结果。 */
    AgentOpsResult execute(AgentOpsRequest request);

    /** 某条备忘的变更历史（新的在前）。 */
    List<MemoChangeLog> history(Long memoId);
}
