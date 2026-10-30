package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.dto.MessageLogItem;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 整理功能要写「谁在哪说了什么」，只有 id 读不出来。
 * 这里钉的是「插件传上来的昵称/群名能原样落到实体」——
 * {@code MessageLogServiceImpl.batchSave} 靠 {@code BeanUtils.copyProperties} 搬运，
 * 少一个同名字段就会静默丢值。
 */
class MessageLogFieldsTest {

    @Test
    void copyProperties_carriesSenderNameAndChatName() {
        MessageLogItem item = new MessageLogItem();
        item.setSenderId(1001L);
        item.setChatId(2002L);
        item.setSenderName("小明");
        item.setChatName("三班群");
        item.setDirection("IN");
        item.setMsgType("text");
        item.setContent("明天下午两点开班会");

        MessageLog log = new MessageLog();
        BeanUtils.copyProperties(item, log);

        assertThat(log.getSenderName()).isEqualTo("小明");
        assertThat(log.getChatName()).isEqualTo("三班群");
    }

    @Test
    void copyProperties_carriesReplyKind() {
        MessageLogItem item = new MessageLogItem();
        item.setSenderId(1001L);
        item.setDirection("OUT");
        item.setMsgType("text");
        item.setReplyKind("CHAT");

        MessageLog log = new MessageLog();
        BeanUtils.copyProperties(item, log);

        assertThat(log.getReplyKind()).isEqualTo("CHAT");
    }
}
