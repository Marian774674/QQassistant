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

    private ChatSetting setting(long chatId, String kind, String name, boolean digestEnabled) {
        ChatSetting setting = new ChatSetting();
        setting.setChatId(chatId);
        setting.setKind(kind);
        setting.setChatName(name);
        setting.setDigestEnabled(digestEnabled);
        setting.setAutoReplyEnabled(false);
        return setting;
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
