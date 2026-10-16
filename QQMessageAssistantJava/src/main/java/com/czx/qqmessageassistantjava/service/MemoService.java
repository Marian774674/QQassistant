package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.dto.MemoCreateRequest;
import com.czx.qqmessageassistantjava.dto.MemoPatchRequest;
import com.czx.qqmessageassistantjava.entity.Draft;
import com.czx.qqmessageassistantjava.entity.Memo;

import java.util.List;

public interface MemoService extends IService<Memo> {

    Memo create(MemoCreateRequest request);

    List<Memo> list(String status,Long senderId);

    Memo patch(Long id, MemoPatchRequest request);

    Memo createFromDraft(Draft draft);

}
