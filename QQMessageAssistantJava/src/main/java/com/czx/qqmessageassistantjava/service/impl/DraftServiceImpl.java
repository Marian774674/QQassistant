package com.czx.qqmessageassistantjava.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.client.LangBotClient;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.*;
import com.czx.qqmessageassistantjava.entity.Draft;
import com.czx.qqmessageassistantjava.mapper.DraftMapper;
import com.czx.qqmessageassistantjava.service.DraftService;
import com.czx.qqmessageassistantjava.service.FileTaskService;
import com.czx.qqmessageassistantjava.service.MemoService;
import com.czx.qqmessageassistantjava.service.ProfileFactService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DraftServiceImpl extends ServiceImpl<DraftMapper, Draft> implements DraftService {

    @Autowired
    private MemoService memoService;
    @Autowired
    private LangBotClient langBotClient;
    @Autowired
    private ProfileFactService profileFactService;
    @Autowired
    private FileTaskService fileTaskService;

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
        if ("FILE".equals(draftCreateRequest.getType())) {
            // 确认时凭这两个字段回传文件；缺一个就永远发不出去，必须在入口拦住
            requireField(draftCreateRequest.getPayload(), "taskRef", "FILE 草稿 payload.taskRef 不能为空");
            requireField(draftCreateRequest.getPayload(), "fileRef", "FILE 草稿 payload.fileRef 不能为空");
        }
        Draft draft = BeanUtil.copyProperties(draftCreateRequest, Draft.class);
        draft.setStatus("PENDING");
        save(draft);
        return draft;
    }

    @Override
    public List<Draft> getDraftList(String type, String status) {
        if(type != null && !TYPES.contains(type)){
            throw new BusinessException("Invalid type, type必须是【" + TYPES + "】其中之一");
        }
        if(status !=null && !STATUS.contains(status)){
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

    void dispatch(Draft draft) {
        switch (draft.getType()) {
            case "MEMO" -> memoService.createFromDraft(draft);
            case "REPLY" -> dispatchReply(draft);
            case "FILE" -> dispatchFile(draft);
            case "FACT" -> dispatchFact(draft);
            default -> throw new BusinessException("未知草稿类型 " + draft.getType());
        }
    }

    private void dispatchReply(Draft draft) {
        Map<String, Object> payload = draft.getPayload();
        SendMessageRequest request = new SendMessageRequest();
        request.setTarget(resolveTarget(payload,draft));
        request.setContent(asString(payload.get("content")));
        request.setBizRef("draft:"+draft.getId());
        langBotClient.sendMessage(request);
    }

    private void dispatchFile(Draft draft) {
        Map<String, Object> payload = draft.getPayload();
        SendFileRequest request = new SendFileRequest();
        request.setTarget(resolveTarget(payload,draft));
        request.setFileName(asString(payload.get("fileName")));
        request.setFileRef(asString(payload.get("fileRef")));
        request.setBizRef("draft:"+draft.getId());
        langBotClient.sendFile(request);
        // 只有发送成功才把镜像状态推进到 SENT；失败时事务回滚，草稿仍是 PENDING
        fileTaskService.updateStatus(asString(payload.get("taskRef")), "SENT");
    }


    private void dispatchFact(Draft draft) {
        Map<String, Object> payload = draft.getPayload();
        FactUpsertRequest request = new FactUpsertRequest();
        request.setSenderId(draft.getSenderId());
        request.setFactKey(asString(payload.get("factKey")));
        request.setFactValue(asString(payload.get("factValue")));
        Object confidence = payload.get("confidence");
        if (confidence instanceof Number number) {
            request.setConfidence(new BigDecimal(number.toString()));
        }
        profileFactService.upsertAuto(request);
    }

    private SendTarget resolveTarget(Map<String, Object> payload, Draft draft) {
        String type = asString(payload.get("targetType"));
        if (type == null || type.isBlank()) {
            // 有 chatId 说明来自群聊，回给群；否则回给 sender 本人
            type = draft.getChatId() != null ? "group" : "private";
        }
        Long id = asLong(payload.get("targetId"));
        if (id == null) {
            id = draft.getChatId();
        }
        if (id == null) {
            id = draft.getSenderId();
        }
        return new SendTarget(type, id);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void requireField(Map<String, Object> payload, String key, String message) {
        String value = asString(payload.get(key));
        if (value == null || value.isBlank()) {
            throw new BusinessException(message);
        }
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            return Long.valueOf(str);
        }
        return null;
    }
}
