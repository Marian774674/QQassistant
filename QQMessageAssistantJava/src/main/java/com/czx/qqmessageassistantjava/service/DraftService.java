package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.dto.DraftConfirmRequest;
import com.czx.qqmessageassistantjava.dto.DraftCreateRequest;
import com.czx.qqmessageassistantjava.entity.Draft;
import org.springframework.stereotype.Service;

import java.util.List;

public interface DraftService extends IService<Draft> {
    Draft createDraft(DraftCreateRequest draftCreateRequest);

    List<Draft> getDraftList(String type, String status);

    /**
     * 确认草稿并按类型分发。
     * <p>{@code request.aliases} 只有 {@code NAMING} 会用；{@code request.content} 只有
     * {@code REPLY} 会用（改写要发出去的正文）。给别的类型传 {@code content} 会报错，
     * 不静默丢弃——否则前端传错字段时，用户会以为「改了却没生效」。
     */
    Draft confirmDraft(Long id, DraftConfirmRequest request);

    /** 兼容老调用方：等价于 {@code confirmDraft(id, null)}。 */
    Draft confirmDraft(Long id);

    Draft rejectDraft(Long id);
}
