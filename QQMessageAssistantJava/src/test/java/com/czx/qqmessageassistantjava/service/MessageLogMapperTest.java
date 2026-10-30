package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「机器人在哪个群里说过话」这条 SQL 决定群设置页能列出哪些群。
 *
 * <p>它以前要求 {@code chat_name} 非空，而群名能不能拿到取决于 LangBot 版本和 NapCat：
 * 实机上一条都没写进去，于是群列表永远是 0，用户连开关都点不到。
 * 群号才是身份，名字只是显示，所以这里钉住「没名字也要能列出来」。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MessageLogMapperTest {

    @Autowired
    private MessageLogMapper messageLogMapper;

    private static final Long NAMELESS_GROUP = 9_961_001L;

    @Test
    void selectGroupPeers_includesGroupWithoutName() {
        messageLogMapper.insert(log(null, NAMELESS_GROUP, "IN", "大家好"));

        assertThat(messageLogMapper.selectGroupPeers())
                .extracting(MessageLog::getChatId)
                .contains(NAMELESS_GROUP);
    }

    private MessageLog log(String name, Long chatId, String direction, String content) {
        MessageLog row = new MessageLog();
        row.setSenderId(9_961_002L);
        row.setSenderName(name);
        row.setChatId(chatId);
        row.setChatName(name);
        row.setDirection(direction);
        row.setMsgType("text");
        row.setContent(content);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }
}
