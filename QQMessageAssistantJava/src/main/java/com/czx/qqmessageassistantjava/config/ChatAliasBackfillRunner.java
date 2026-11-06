package com.czx.qqmessageassistantjava.config;

import com.czx.qqmessageassistantjava.service.ChatAliasService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动后给库里已有的群和私聊对象补命名草稿。
 *
 * <p>幂等：已经有别名行或命名草稿的会话直接跳过，重启多少次都只补缺的那几条。
 * 补发失败只打日志，不能拦住应用启动。
 */
@Component
public class ChatAliasBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(ChatAliasBackfillRunner.class);

    private final ChatAliasService chatAliasService;

    public ChatAliasBackfillRunner(ChatAliasService chatAliasService) {
        this.chatAliasService = chatAliasService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        try {
            int created = chatAliasService.backfillNamingDrafts();
            if (created > 0) {
                log.info("已为 {} 个历史会话补发命名草稿", created);
            }
        } catch (RuntimeException e) {
            log.warn("历史会话命名草稿补发失败: {}", e.getMessage());
        }
    }
}
