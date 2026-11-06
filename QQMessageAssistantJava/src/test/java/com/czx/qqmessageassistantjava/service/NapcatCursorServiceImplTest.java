package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.entity.ChatSetting;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.entity.NapcatCursor;
import com.czx.qqmessageassistantjava.mapper.ChatSettingMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.mapper.NapcatCursorMapper;
import com.czx.qqmessageassistantjava.service.impl.NapcatCursorServiceImpl;
import com.czx.qqmessageassistantjava.vo.NapcatTargetVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补拉目标与游标：目标挑错了会白打几十次 QQ 接口（甚至触发风控），
 * 游标回退了会把几百条历史重拉一遍，所以这里钉的是「挑谁」和「只前进」。
 */
@ExtendWith(MockitoExtension.class)
class NapcatCursorServiceImplTest {

    @Mock
    NapcatCursorMapper napcatCursorMapper;

    @Mock
    MessageLogMapper messageLogMapper;

    @Mock
    ChatSettingMapper chatSettingMapper;

    private NapcatCursorServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NapcatCursorServiceImpl(napcatCursorMapper, messageLogMapper, chatSettingMapper);
    }

    @Test
    void targetsMergeGroupsPrivatesAndEnabledSettingsWithoutDuplicates() {
        when(chatSettingMapper.selectList(any())).thenReturn(List.of(
                setting(900L, "GROUP", "三班群", 48)));
        when(messageLogMapper.selectRecentGroupPeers(any(), anyInt()))
                .thenReturn(List.of(group(900L, "三班群", 1), group(901L, "四班群", 2)));
        when(messageLogMapper.selectRecentPrivatePeers(any(), anyInt()))
                .thenReturn(List.of(peer(7001L, 3)));
        when(napcatCursorMapper.selectList(null)).thenReturn(List.of());

        List<NapcatTargetVO> targets = service.targets(50);

        // 900 在 chat_setting 与 message_log 两路里都出现，只能有一条
        assertThat(targets)
                .extracting(NapcatTargetVO::getChatType, NapcatTargetVO::getChatId)
                .containsExactly(
                        tuple("GROUP", 900L),
                        tuple("GROUP", 901L),
                        tuple("PRIVATE", 7001L));
        assertThat(targets).allSatisfy(t -> assertThat(t.getLastMessageSeq()).isEqualTo(0L));
    }

    @Test
    void enabledChatsComeFirstEvenWhenAnotherChatIsMoreActive() {
        // 900 是用户明确开过开关的，但最近 30 天没说话；901 一小时前刚聊过。
        // 撞上 50 个上限时不能把 900 挤掉——掉线漏消息只发生在用户真正关心的会话里。
        when(chatSettingMapper.selectList(any())).thenReturn(List.of(
                setting(900L, "GROUP", "三班群", 24 * 30)));
        when(messageLogMapper.selectRecentGroupPeers(any(), anyInt()))
                .thenReturn(List.of(group(901L, "四班群", 1), group(900L, "三班群", 24 * 30)));
        when(messageLogMapper.selectRecentPrivatePeers(any(), anyInt())).thenReturn(List.of());
        when(napcatCursorMapper.selectList(null)).thenReturn(List.of());

        assertThat(service.targets(50)).extracting(NapcatTargetVO::getChatId)
                .containsExactly(900L, 901L);
    }

    @Test
    void targetsCarryStoredCursorAndFallBackToZero() {
        when(chatSettingMapper.selectList(any())).thenReturn(List.of());
        when(messageLogMapper.selectRecentGroupPeers(any(), anyInt()))
                .thenReturn(List.of(group(900L, "三班群", 1), group(901L, "四班群", 2)));
        when(messageLogMapper.selectRecentPrivatePeers(any(), anyInt())).thenReturn(List.of());
        when(napcatCursorMapper.selectList(null))
                .thenReturn(List.of(cursor("GROUP", 900L, 12345L)));

        List<NapcatTargetVO> targets = service.targets(50);

        assertThat(targets).filteredOn(t -> t.getChatId().equals(900L))
                .singleElement()
                .extracting(NapcatTargetVO::getLastMessageSeq).isEqualTo(12345L);
        assertThat(targets).filteredOn(t -> t.getChatId().equals(901L))
                .singleElement()
                .extracting(NapcatTargetVO::getLastMessageSeq).isEqualTo(0L);
    }

    @Test
    void privateTargetsNeverCarryAChatName() {
        when(chatSettingMapper.selectList(any())).thenReturn(List.of());
        when(messageLogMapper.selectRecentGroupPeers(any(), anyInt())).thenReturn(List.of());
        // 私聊的 MAX(sender_name) 是对方昵称，不是群名，不能顶到 chatName 上——
        // 否则补回来的日志和实时日志长得不一样
        when(messageLogMapper.selectRecentPrivatePeers(any(), anyInt()))
                .thenReturn(List.of(peer(7001L, 1)));
        when(napcatCursorMapper.selectList(null)).thenReturn(List.of());

        assertThat(service.targets(50)).singleElement()
                .extracting(NapcatTargetVO::getChatName).isNull();
    }

    @Test
    void limitCapsTheResultAndDefaultsToFifty() {
        when(chatSettingMapper.selectList(any())).thenReturn(List.of());
        when(messageLogMapper.selectRecentGroupPeers(any(), anyInt()))
                .thenReturn(List.of(group(1L, "g1", 1), group(2L, "g2", 2), group(3L, "g3", 3)));
        when(messageLogMapper.selectRecentPrivatePeers(any(), anyInt())).thenReturn(List.of());
        when(napcatCursorMapper.selectList(null)).thenReturn(List.of());

        assertThat(service.targets(1)).hasSize(1);
        assertThat(service.targets(null)).hasSize(3);

        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(messageLogMapper, times(2)).selectRecentGroupPeers(any(), limits.capture());
        assertThat(limits.getAllValues()).containsExactly(1, 50);
    }

    @Test
    void advanceWritesThroughAndReturnsTheStoredValue() {
        when(napcatCursorMapper.selectOne(any())).thenReturn(cursor("GROUP", 900L, 200L));

        // SQL 里是 GREATEST：写 100 进去实际游标仍是 200，必须回读而不是回显入参
        assertThat(service.advance("group", 900L, 100L)).isEqualTo(200L);
        verify(napcatCursorMapper).upsert(eq("GROUP"), eq(900L), eq(100L), any());
    }

    @Test
    void advanceRejectsBadKindBlankChatAndNegativeSeq() {
        assertThatThrownBy(() -> service.advance("FRIEND", 1L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("chatType");
        assertThatThrownBy(() -> service.advance("GROUP", null, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("chatId");
        assertThatThrownBy(() -> service.advance("GROUP", 1L, -1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("lastMessageSeq");
        verify(napcatCursorMapper, never()).upsert(any(), any(), any(), any());
    }

    private static ChatSetting setting(Long chatId, String kind, String name, int hoursAgo) {
        ChatSetting row = new ChatSetting();
        row.setChatId(chatId);
        row.setKind(kind);
        row.setChatName(name);
        row.setAutoReplyEnabled(true);
        row.setDigestEnabled(false);
        row.setUpdatedAt(LocalDateTime.now().minusHours(hoursAgo));
        return row;
    }

    private static MessageLog group(Long chatId, String name, int hoursAgo) {
        MessageLog row = new MessageLog();
        row.setChatId(chatId);
        row.setChatName(name);
        row.setCreatedAt(LocalDateTime.now().minusHours(hoursAgo));
        return row;
    }

    private static MessageLog peer(Long senderId, int hoursAgo) {
        MessageLog row = new MessageLog();
        row.setSenderId(senderId);
        row.setChatName("小明");
        row.setCreatedAt(LocalDateTime.now().minusHours(hoursAgo));
        return row;
    }

    private static NapcatCursor cursor(String kind, Long chatId, Long seq) {
        NapcatCursor row = new NapcatCursor();
        row.setChatType(kind);
        row.setChatId(chatId);
        row.setLastMessageSeq(seq);
        return row;
    }
}
