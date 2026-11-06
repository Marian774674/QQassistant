package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.dto.DraftCreateRequest;
import com.czx.qqmessageassistantjava.entity.Draft;
import org.springframework.stereotype.Service;

import java.util.List;

public interface DraftService extends IService<Draft> {
    Draft createDraft(DraftCreateRequest draftCreateRequest);

    List<Draft> getDraftList(String type, String status);

    /** 确认草稿；{@code aliases} 只有 NAMING 类型会用，其他类型忽略。 */
    Draft confirmDraft(Long id, String aliases);

    /** 兼容老调用方：等价于 {@code confirmDraft(id, null)}。 */
    Draft confirmDraft(Long id);

    Draft rejectDraft(Long id);
}
