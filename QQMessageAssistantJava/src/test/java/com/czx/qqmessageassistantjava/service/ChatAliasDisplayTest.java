package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.retrieval.SourceFormat;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAliasDisplayTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 28, 21, 3);

    @Test
    void groupSourceUsesAliasInsteadOfNumber() {
        MessageLog row = row(2002L, 1001L, "三班群", "小明");

        assertThat(SourceFormat.messageSource(row, "班群"))
                .isEqualTo("班群 · 小明 · 10-28 21:03");
    }

    @Test
    void privateSourceUsesAliasInsteadOfQQ() {
        MessageLog row = row(null, 777L, null, "阿黄");

        assertThat(SourceFormat.messageSource(row, "黄哥"))
                .isEqualTo("黄哥 · 阿黄 · 10-28 21:03");
    }

    @Test
    void sourceFallsBackToChatNameWhenAliasMissing() {
        MessageLog row = row(2002L, 1001L, "三班群", "小明");

        assertThat(SourceFormat.messageSource(row, null))
                .isEqualTo("三班群 · 小明 · 10-28 21:03");
    }

    @Test
    void sourceFallsBackToNumberWhenNothingElse() {
        MessageLog group = row(2002L, 1001L, null, null);
        MessageLog peer = row(null, 777L, null, null);

        assertThat(SourceFormat.messageSource(group, null)).startsWith("群 2002");
        assertThat(SourceFormat.messageSource(peer, null)).startsWith("QQ 777");
    }

    private MessageLog row(Long chatId, Long senderId, String chatName, String senderName) {
        MessageLog row = new MessageLog();
        row.setChatId(chatId);
        row.setSenderId(senderId);
        row.setChatName(chatName);
        row.setSenderName(senderName);
        row.setCreatedAt(AT);
        return row;
    }
}
