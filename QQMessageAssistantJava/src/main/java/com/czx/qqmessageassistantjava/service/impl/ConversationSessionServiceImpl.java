package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.ConversationBindRequest;
import com.czx.qqmessageassistantjava.entity.ConversationSession;
import com.czx.qqmessageassistantjava.mapper.ConversationSessionMapper;
import com.czx.qqmessageassistantjava.service.ConversationSessionService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ConversationSessionServiceImpl
        extends ServiceImpl<ConversationSessionMapper, ConversationSession>
        implements ConversationSessionService {

    private static final String ACTIVE = "ACTIVE";
    private static final String RESET = "RESET";

    @Override
    public String resolve(String botUuid, String chatType, Long chatId, Long senderId) {
        ConversationSession session = findOne(
                requireText(botUuid, "botUuid"),
                normalizeChatType(chatType),
                normalizeChatId(chatType, chatId),
                requireSender(senderId));
        return session != null && ACTIVE.equals(session.getStatus())
                ? session.getDifyConversationId() : null;
    }

    @Override
    public ConversationSession bind(ConversationBindRequest request) {
        String botUuid = requireText(request.getBotUuid(), "botUuid");
        String chatType = normalizeChatType(request.getChatType());
        Long chatId = normalizeChatId(chatType, request.getChatId());
        Long senderId = requireSender(request.getSenderId());
        String conversationId = requireText(request.getConversationId(), "conversationId");

        ConversationSession existing = findOne(botUuid, chatType, chatId, senderId);
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            existing.setDifyConversationId(conversationId);
            existing.setStatus(ACTIVE);
            existing.setLastActiveAt(now);
            updateById(existing);
            return existing;
        }

        ConversationSession session = new ConversationSession();
        session.setBotUuid(botUuid);
        session.setChatType(chatType);
        session.setChatId(chatId);
        session.setSenderId(senderId);
        session.setDifyConversationId(conversationId);
        session.setStatus(ACTIVE);
        session.setLastActiveAt(now);
        try {
            save(session);
        } catch (DuplicateKeyException e) {
            ConversationSession winner = findOne(botUuid, chatType, chatId, senderId);
            if (winner != null) {
                winner.setDifyConversationId(conversationId);
                winner.setStatus(ACTIVE);
                winner.setLastActiveAt(now);
                updateById(winner);
                return winner;
            }
            throw e;
        }
        return session;
    }

    @Override
    public void reset(ConversationBindRequest request) {
        ConversationSession existing = findOne(
                requireText(request.getBotUuid(), "botUuid"),
                normalizeChatType(request.getChatType()),
                normalizeChatId(request.getChatType(), request.getChatId()),
                requireSender(request.getSenderId()));
        if (existing == null) {
            return;
        }
        existing.setStatus(RESET);
        updateById(existing);
    }

    private ConversationSession findOne(String botUuid, String chatType,
                                        Long chatId, Long senderId) {
        return lambdaQuery()
                .eq(ConversationSession::getBotUuid, botUuid)
                .eq(ConversationSession::getChatType, chatType)
                .eq(ConversationSession::getChatId, chatId)
                .eq(ConversationSession::getSenderId, senderId)
                .one();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(field + " 不能为空");
        }
        return value.trim();
    }

    private static Long requireSender(Long senderId) {
        if (senderId == null) {
            throw new BusinessException("senderId 不能为空");
        }
        return senderId;
    }

    private static String normalizeChatType(String chatType) {
        String value = requireText(chatType, "chatType");
        if (!"private".equals(value) && !"group".equals(value)) {
            throw new BusinessException("chatType 必须是 private 或 group");
        }
        return value;
    }

    private static Long normalizeChatId(String chatType, Long chatId) {
        if ("group".equals(normalizeChatType(chatType))) {
            if (chatId == null || chatId <= 0) {
                throw new BusinessException("群聊 chatId 不能为空");
            }
            return chatId;
        }
        return 0L;
    }
}
