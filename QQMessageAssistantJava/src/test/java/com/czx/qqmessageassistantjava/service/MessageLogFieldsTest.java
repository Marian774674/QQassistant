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

    /**
     * {@code createdAtEpochSeconds} 在实体里没有同名属性（类型也不同：Long vs LocalDateTime）。
     *
     * <p>Spring 的 {@code BeanUtils.copyProperties} 遇到类型不可赋值的属性会**静默跳过**，
     * 不会抛 {@code FatalBeanException}——这条测试钉的就是这个前提：
     * 补拉的时间换算由 {@code MessageLogServiceImpl} 显式做，不能指望拷贝。
     */
    @Test
    void copyProperties_skipsTheEpochFieldInsteadOfThrowing() {
        MessageLogItem item = new MessageLogItem();
        item.setSenderId(1001L);
        item.setDirection("IN");
        item.setMsgType("text");
        item.setCreatedAtEpochSeconds(1_767_294_245L);

        MessageLog log = new MessageLog();
        BeanUtils.copyProperties(item, log);

        assertThat(log.getCreatedAt()).isNull();
    }
}
