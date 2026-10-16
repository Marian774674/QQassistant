package com.czx.qqmessageassistantjava.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.DraftCreateRequest;
import com.czx.qqmessageassistantjava.entity.Draft;
import com.czx.qqmessageassistantjava.mapper.DraftMapper;
import com.czx.qqmessageassistantjava.service.DraftService;
import com.czx.qqmessageassistantjava.service.MemoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class DraftServiceImpl extends ServiceImpl<DraftMapper, Draft> implements DraftService {

    @Autowired
    private MemoService memoService;

    private static final Set<String> TYPES = Set.of("REPLY","FILE","MEMO","FACT");
    private static final Set<String> STATUS = Set.of("PENDING","CONFIRMED","REJECTED");

    @Override
    public Draft createDraft(DraftCreateRequest draftCreateRequest) {
        if(draftCreateRequest.getType() == null || !TYPES.contains(draftCreateRequest.getType())){
            throw new BusinessException("Invalid type, type必须是【" + TYPES + "】其中之一");
        }
        if(draftCreateRequest.getSenderId() == null){
            throw new BusinessException("Sender ID不能为空");
        }
        if(draftCreateRequest.getPayload() == null){
            throw new BusinessException("Payload不能为空");
        }
        Draft draft = BeanUtil.copyProperties(draftCreateRequest, Draft.class);
        draft.setStatus("PENDING");
        save(draft);
        return draft;
    }

    @Override
    public List<Draft> getDraftList(String type, String status) {
        if(type != null || !TYPES.contains(type)){
            throw new BusinessException("Invalid type, type必须是【" + TYPES + "】其中之一");
        }
        if(status !=null || !STATUS.contains(status)){
            throw new BusinessException("Invalid status, status必须是【" + STATUS + "】其中之一");
        }
        return lambdaQuery()
                .eq(type != null, Draft::getType,type)
                .eq(status != null, Draft::getStatus,status)
                .orderByDesc(Draft::getCreatedAt)
                .list();
    }

    @Override
    @Transactional
    public Draft confirmDraft(Long id) {
        Draft draft = isPending(id);
        draft.setConfirmedAt(LocalDateTime.now());
        draft.setStatus("CONFIRMED");
        updateById(draft);
        dispatch(draft);
        return draft;
    }

    @Override
    public Draft rejectDraft(Long id) {
        Draft draft = isPending(id);
        draft.setStatus("REJECTED");
        updateById(draft);
        return draft;
    }

    private Draft isPending(Long id){
        Draft draft = getById(id);
        if(draft == null){
            throw new BusinessException("草稿不存在");
        }
        if(!"PENDING".equals(draft.getStatus())){
            throw new BusinessException("草稿状态不是Pending,当前草稿状态是" + draft.getStatus());
        }
        return draft;
    }

    private void dispatch(Draft draft) {
        switch (draft.getType()){
            case "MEMO":
                memoService.createFromDraft(draft);
                break;
            case "FILE":
//                TODO: 处理File
                break;
            case "FACT":
//                TODO: 处理Fact
                break;
        }
    }
}
