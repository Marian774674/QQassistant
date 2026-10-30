package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.entity.ChatSetting;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.ChatSettingMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.impl.ChatSettingServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSettingServiceImplTest {

    @Mock
    ChatSettingMapper mapper;

    @Mock
    MessageLogMapper messageLogMapper;

    @InjectMocks
    ChatSettingServiceImpl service;

    @Test
    void listAll_includesGroupsSeenInLogsButNotConfiguredYet() {
        when(mapper.selectList(any())).thenReturn(List.of(
                setting(9L, "GROUP", "三班群", true)));
        when(messageLogMapper.selectGroupPeers()).thenReturn(List.of(
                peer(9L, "三班群"),
                peer(10L, "游戏群")));

        List<ChatSetting> all = service.listAll();

        assertThat(all).extracting(ChatSetting::getChatId).containsExactly(9L, 10L);
        ChatSetting discovered = all.get(1);
        assertThat(discovered.getChatName()).isEqualTo("游戏群");
        assertThat(discovered.getKind()).isEqualTo("GROUP");
        assertThat(discovered.getDigestEnabled()).isFalse();
        assertThat(discovered.getAutoReplyEnabled()).isFalse();
    }

    @Test
    void listAll_includesPrivatePeersSeenInLogs() {
        when(mapper.selectList(any())).thenReturn(List.of(
                setting(9L, "GROUP", "三班群", true)));
        when(messageLogMapper.selectGroupPeers()).thenReturn(List.of(peer(9L, "三班群")));
        // 私聊没有群号：会话键是对方 QQ，昵称存在 sender_name 里
        when(messageLogMapper.selectPrivatePeers()).thenReturn(List.of(privatePeer(777L, "阿黄")));

        List<ChatSetting> all = service.listAll();

        assertThat(all).extracting(ChatSetting::getChatId).containsExactlyInAnyOrder(9L, 777L);
        assertThat(all).extracting(ChatSetting::getKind).containsExactlyInAnyOrder("GROUP", "PRIVATE");
        ChatSetting privateChat = all.stream()
                .filter(s -> "PRIVATE".equals(s.getKind())).findFirst().orElseThrow();
        assertThat(privateChat.getChatName()).isEqualTo("阿黄");
        assertThat(privateChat.getAutoReplyEnabled()).isFalse();
        // 私聊不整理，只做自动回复
        assertThat(privateChat.getDigestEnabled()).isFalse();
    }

    private ChatSetting setting(long chatId, String kind, String name, boolean digestEnabled) {
        ChatSetting setting = new ChatSetting();
        setting.setChatId(chatId);
        setting.setKind(kind);
        setting.setChatName(name);
        setting.setDigestEnabled(digestEnabled);
        setting.setAutoReplyEnabled(false);
        return setting;
    }

    @Test
    void listAll_includesGroupWhoseNameNeverArrived() {
        // 群名不是每版 LangBot 都给得出来：拿不到名字的群也得列出来，
        // 否则「收到了群消息但群里一条开关都看不到」
        when(mapper.selectList(any())).thenReturn(List.of());
        when(messageLogMapper.selectGroupPeers()).thenReturn(List.of(peer(10L, null)));

        List<ChatSetting> all = service.listAll();

        assertThat(all).extracting(ChatSetting::getChatId).containsExactly(10L);
        assertThat(all.get(0).getKind()).isEqualTo("GROUP");
        assertThat(all.get(0).getChatName()).isEqualTo("群 10");
    }

    @Test
    void listAll_privatePeerWithoutNameStillListed() {
        when(mapper.selectList(any())).thenReturn(List.of());
        when(messageLogMapper.selectGroupPeers()).thenReturn(List.of());
        when(messageLogMapper.selectPrivatePeers()).thenReturn(List.of(privatePeer(777L, null)));

        List<ChatSetting> all = service.listAll();

        assertThat(all).extracting(ChatSetting::getChatId).containsExactly(777L);
        assertThat(all.get(0).getChatName()).isEqualTo("QQ 777");
    }

    /** 私聊对象：mapper 查 sender_id，昵称用 sender_name AS chat_name 塞进 chatName */
    private MessageLog privatePeer(long senderId, String name) {
        MessageLog log = new MessageLog();
        log.setSenderId(senderId);
        log.setSenderName(name);
        log.setChatName(name);
        return log;
    }

    private MessageLog peer(long chatId, String chatName) {
        MessageLog log = new MessageLog();
        log.setChatId(chatId);
        log.setChatName(chatName);
        return log;
    }

    @Test
    void upsert_rejectsIllegalKind() {
        assertThatThrownBy(() -> service.upsert(1L, "CHANNEL", null, true, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("kind");
    }

    @Test
    void upsert_rejectsMissingChatId() {
        assertThatThrownBy(() -> service.upsert(null, "GROUP", "三班群", true, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("chatId");
    }
}
