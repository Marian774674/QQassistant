package com.czx.qqmessageassistantjava.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.client.LangBotClient;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.*;
import com.czx.qqmessageassistantjava.entity.Draft;
import com.czx.qqmessageassistantjava.mapper.DraftMapper;
import com.czx.qqmessageassistantjava.service.ChatAliasService;
import com.czx.qqmessageassistantjava.service.DraftService;
import com.czx.qqmessageassistantjava.service.FileTaskService;
import com.czx.qqmessageassistantjava.service.MemoService;
import com.czx.qqmessageassistantjava.service.ProfileFactService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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
    @Autowired
    private ChatAliasService chatAliasService;

    private static final Set<String> TYPES = Set.of("REPLY","FILE","MEMO","FACT","NAMING");
    private static final Set<String> STATUS = Set.of("PENDING","CONFIRMED","REJECTED");
    /** 改写正文的上限。QQ 单条文本本身也有上限，这里只拦「误贴一整篇」这种明显手滑 */
    private static final int MAX_REPLY_LENGTH = 2000;

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
        List<Draft> drafts = getBaseMapper().selectList(new LambdaQueryWrapper<Draft>()
                .eq(type != null, Draft::getType, type)
                .eq(status != null, Draft::getStatus, status)
                .orderByDesc(Draft::getCreatedAt));
        // 回复草稿要显示「对方」起的名字；只放第一个，别把整串称呼抖出来
        Map<String, String> aliases = chatAliasService.displayNames();
        if (aliases != null) {
            for (Draft draft : drafts) {
                attachAliasDisplay(draft, aliases);
            }
        }
        return drafts;
    }

    private void attachAliasDisplay(Draft draft, Map<String, String> aliases) {
        if (!"REPLY".equals(draft.getType())) {
            return;
        }
        Map<String, Object> payload = draft.getPayload();
        if (payload == null) {
            return;
        }
        // 私聊的「对方」是 senderId；群聊的「对方」是群号。两把钥匙都带 kind，避免同号串台
        String key = draft.getChatId() != null
                ? ChatAliasService.key(draft.getChatId(), "GROUP")
                : draft.getSenderId() != null
                        ? ChatAliasService.key(draft.getSenderId(), "PRIVATE")
                        : null;
        String name = key == null ? null : aliases.get(key);
        if (name == null || name.isBlank()) {
            return;
        }
        Map<String, Object> enriched = new LinkedHashMap<>(payload);
        enriched.put("sourceDisplayName", name);
        draft.setPayload(enriched);
    }

    @Override
    @Transactional
    public Draft confirmDraft(Long id) {
        return confirmDraft(id, null);
    }

    @Override
    @Transactional
    public Draft confirmDraft(Long id, DraftConfirmRequest request) {
        Draft draft = isPending(id);
        if ("NAMING".equals(draft.getType())) {
            // 命名草稿的「分发」就是写别名，没有要向 QQ 发的东西
            confirmNaming(draft, request == null ? null : request.getAliases());
            return draft;
        }
        // 先改写再落库：dispatchReply 读的是 payload.content，改晚了发的还是原文
        if (request != null && request.getContent() != null) {
            applyEditedContent(draft, request.getContent());
        }
        draft.setConfirmedAt(LocalDateTime.now());
        draft.setStatus("CONFIRMED");
        updateById(draft);
        dispatch(draft);
        return draft;
    }

    /**
     * 把用户改写过的正文写回 payload，之后 {@link #dispatchReply} 发出去的就是这一段。
     * <p>原正文另存 {@code payload.originalContent}：模型当时写了什么是调提示词的重要反馈；
     * 而 {@code payload.content} 必须是「实际发出去的那句话」，否则「已处理」列表会骗人。
     */
    private void applyEditedContent(Draft draft, String content) {
        if (!"REPLY".equals(draft.getType())) {
            throw new BusinessException("只有回复草稿可以改内容，当前草稿类型是 " + draft.getType());
        }
        String edited = content == null ? "" : content.trim();
        if (edited.isEmpty()) {
            // 发一条空消息在 QQ 上等于什么都没发生，用户还会以为「发了」
            throw new BusinessException("回复内容不能为空");
        }
        if (edited.length() > MAX_REPLY_LENGTH) {
            throw new BusinessException("回复内容最多 " + MAX_REPLY_LENGTH + " 字");
        }
        Map<String, Object> payload = draft.getPayload() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(draft.getPayload());
        String original = asString(payload.get("content"));
        if (edited.equals(original == null ? "" : original.trim())) {
            // 只是多打了几个空格，别留下「已改写」的痕迹
            return;
        }
        payload.put("originalContent", original);
        payload.put("content", edited);
        payload.put("edited", true);
        draft.setPayload(payload);
    }

    private void confirmNaming(Draft draft, String aliases) {
        // 只把逗号、空格喂进来时 isBlank 是 false，但清洗后一个名字都不剩——
        // 落库会写成空串（「用户拒绝命名」的哨兵），界面却显示「已保存」
        if (ChatAliasServiceImpl.parse(aliases).isEmpty()) {
            throw new BusinessException("先给它起个名字");
        }
        Map<String, Object> payload = draft.getPayload();
        Long chatId = payload == null ? null : asLong(payload.get("chatId"));
        String kind = payload == null ? null : asString(payload.get("kind"));
        if (chatId == null || kind == null || kind.isBlank()) {
            throw new BusinessException("命名草稿缺少会话信息");
        }
        chatAliasService.saveAliases(chatId, kind, aliases);
        payload.put("aliases", aliases);
        draft.setPayload(payload);
        draft.setConfirmedAt(LocalDateTime.now());
        draft.setStatus("CONFIRMED");
        updateById(draft);
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
