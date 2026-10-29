package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.dto.MemoCreateRequest;
import com.czx.qqmessageassistantjava.dto.MemoPatchRequest;
import com.czx.qqmessageassistantjava.entity.Draft;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.entity.MemoReminder;
import com.czx.qqmessageassistantjava.vo.MemoOpenItem;

import java.util.List;

public interface MemoService extends IService<Memo> {

    Memo create(MemoCreateRequest request);

    List<Memo> list(String status,Long senderId);

    Memo patch(Long id, MemoPatchRequest request);

    Memo createFromDraft(Draft draft);

    /** 某条备忘的提醒计划（按提醒时间升序），供前端展示"什么时候会提醒我"。 */
    List<MemoReminder> reminders(Long memoId);

    /**
     * 未终结的备忘摘要（ACTIVE/REMINDED/AWAITING_REPLY/OVERDUE），
     * 按 dueTime 升序（没有时间的排后面）。喂给 Dify 让它能指认"改哪一条"。
     */
    List<MemoOpenItem> openMemos(Long senderId, int limit);

}
