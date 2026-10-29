package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.dto.ConversationBindRequest;
import com.czx.qqmessageassistantjava.entity.ConversationSession;

public interface ConversationSessionService extends IService<ConversationSession> {

    /** 取当前 ACTIVE 的 Dify conversation_id；没有返回 null。 */
    String resolve(String botUuid, String chatType, Long chatId, Long senderId);

    /** 首次创建或覆盖当前会话映射。 */
    ConversationSession bind(ConversationBindRequest request);

    /** 标记当前映射失效；下次 bind 会创建新会话。 */
    void reset(ConversationBindRequest request);
}
