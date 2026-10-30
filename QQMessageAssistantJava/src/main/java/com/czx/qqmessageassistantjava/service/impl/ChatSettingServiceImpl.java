package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.entity.ChatSetting;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.ChatSettingMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.ChatSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChatSettingServiceImpl extends ServiceImpl<ChatSettingMapper, ChatSetting>
        implements ChatSettingService {

    private static final Set<String> KINDS = Set.of("GROUP", "PRIVATE");

    // 直接持有 mapper：继承来的 baseMapper 在单测里不会被 Mockito 注入，
    // 而这几个查询本来也不需要 IService 的包装
    @Autowired
    private ChatSettingMapper chatSettingMapper;

    @Autowired
    private MessageLogMapper messageLogMapper;

    @Override
    public List<ChatSetting> listAll() {
        List<ChatSetting> rows = chatSettingMapper.selectList(new LambdaQueryWrapper<ChatSetting>()
                .orderByAsc(ChatSetting::getKind)
                .orderByAsc(ChatSetting::getChatName));
        // 日志里见过、但还没建开关行的群也列出来（开关默认关），否则用户永远没法给新群开整理
        Set<Long> known = rows.stream().map(ChatSetting::getChatId).collect(Collectors.toSet());
        List<ChatSetting> merged = new ArrayList<>(rows);
        for (MessageLog peer : messageLogMapper.selectGroupPeers()) {
            if (peer.getChatId() == null || !known.add(peer.getChatId())) {
                continue;
            }
            ChatSetting discovered = new ChatSetting();
            discovered.setChatId(peer.getChatId());
            discovered.setKind("GROUP");
            discovered.setChatName(peer.getChatName());
            discovered.setDigestEnabled(false);
            discovered.setAutoReplyEnabled(false);
            merged.add(discovered);
        }
        return merged;
    }

    @Override
    public List<Long> listEnabledGroupIds() {
        return chatSettingMapper.selectList(new LambdaQueryWrapper<ChatSetting>()
                        .eq(ChatSetting::getKind, "GROUP")
                        .eq(ChatSetting::getDigestEnabled, true))
                .stream()
                .map(ChatSetting::getChatId)
                .toList();
    }

    @Override
    @Transactional
    public ChatSetting upsert(Long chatId, String kind, String chatName,
                              Boolean digestEnabled, Boolean autoReplyEnabled) {
        if (kind == null || !KINDS.contains(kind)) {
            throw new BusinessException("kind 只能是 GROUP 或 PRIVATE");
        }
        if (chatId == null) {
            throw new BusinessException("chatId 不能为空");
        }
        ChatSetting existing = chatSettingMapper.selectOne(new LambdaQueryWrapper<ChatSetting>()
                .eq(ChatSetting::getChatId, chatId)
                .eq(ChatSetting::getKind, kind));
        if (existing == null) {
            ChatSetting created = new ChatSetting();
            created.setChatId(chatId);
            created.setKind(kind);
            created.setChatName(chatName);
            created.setAutoReplyEnabled(Boolean.TRUE.equals(autoReplyEnabled));
            created.setDigestEnabled(Boolean.TRUE.equals(digestEnabled));
            save(created);
            return created;
        }
        // null 表示「这次不改这一项」，别把前端没传的开关顺手关掉
        if (chatName != null && !chatName.isBlank()) {
            existing.setChatName(chatName);
        }
        if (digestEnabled != null) {
            existing.setDigestEnabled(digestEnabled);
        }
        if (autoReplyEnabled != null) {
            existing.setAutoReplyEnabled(autoReplyEnabled);
        }
        updateById(existing);
        return existing;
    }
}
