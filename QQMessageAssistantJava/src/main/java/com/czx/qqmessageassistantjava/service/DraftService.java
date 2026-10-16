package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.dto.DraftCreateRequest;
import com.czx.qqmessageassistantjava.entity.Draft;
import org.springframework.stereotype.Service;

import java.util.List;

public interface DraftService extends IService<Draft> {
    Draft createDraft(DraftCreateRequest draftCreateRequest);

    List<Draft> getDraftList(String type, String status);

    Draft confirmDraft(Long id);

    Draft rejectDraft(Long id);
}
