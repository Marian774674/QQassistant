package com.czx.qqmessageassistantjava.service.impl;

import com.czx.qqmessageassistantjava.entity.ChatSetting;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import com.czx.qqmessageassistantjava.service.ChatDigestService;
import com.czx.qqmessageassistantjava.service.ChatDigestService.DigestOutcome;
import com.czx.qqmessageassistantjava.service.ChatSettingService;
import com.czx.qqmessageassistantjava.vo.DigestRefreshVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DigestRefreshServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 15, 0);
    private static final LocalDateTime LAST_RUN = NOW.minusMinutes(30);

    @Mock ChatDigestService chatDigestService;
    @Mock ChatSettingService chatSettingService;
    @Mock AppSettingService appSettingService;
    @Mock MessageLogMapper messageLogMapper;

    private DigestRefreshService service;

    @BeforeEach
    void setUp() {
        service = new DigestRefreshService(
                chatDigestService, chatSettingService, appSettingService, messageLogMapper);
    }

    @Test
    void belowBothThresholds_leavesTheWindowAlone() {
        stubSettings("1", LAST_RUN, "100", "60");
        when(chatSettingService.listEnabledGroupIds()).thenReturn(List.of(9L));
        when(messageLogMapper.selectCount(any())).thenReturn(40L);

        DigestRefreshVO result = service.refresh(NOW);

        assertThat(result.isRefreshed()).isFalse();
        assertThat(result.getMessageCount()).isEqualTo(40);
        assertThat(result.getMinutesSince()).isEqualTo(30);
        verify(chatDigestService, never()).digestChat(any(), anyString(), any(), any());
        verify(appSettingService, never()).putAll(any());
    }

    @Test
    void tooManyMessages_triggersDigestOfEnabledGroups() {
        stubSettings("1", LAST_RUN, "100", "60");
        when(chatSettingService.listEnabledGroupIds()).thenReturn(List.of(9L, 10L));
        when(messageLogMapper.selectCount(any())).thenReturn(101L);
        when(chatSettingService.listAll()).thenReturn(List.of(
                group(9L, "三班群", true),
                group(10L, "游戏群", false)));
        when(chatDigestService.digestChat(9L, "三班群", LAST_RUN, NOW)).thenReturn(new DigestOutcome(3, false));

        DigestRefreshVO result = service.refresh(NOW);

        assertThat(result.isRefreshed()).isTrue();
        assertThat(result.getSaved()).isEqualTo(3);
        verify(chatDigestService).digestChat(9L, "三班群", LAST_RUN, NOW);
        verify(chatDigestService, never()).digestChat(eq(10L), anyString(), any(), any());
        verify(appSettingService).putAll(Map.of(DigestRefreshService.LAST_RUN_KEY, NOW.toString()));
    }

    @Test
    void longGap_triggersEvenWithFewMessages() {
        LocalDateTime twoHoursAgo = NOW.minusHours(2);
        stubSettings("1", twoHoursAgo, "100", "60");
        when(chatSettingService.listEnabledGroupIds()).thenReturn(List.of(9L));
        when(messageLogMapper.selectCount(any())).thenReturn(2L);
        when(chatSettingService.listAll()).thenReturn(List.of(group(9L, "三班群", true)));
        when(chatDigestService.digestChat(9L, "三班群", twoHoursAgo, NOW)).thenReturn(new DigestOutcome(1, false));

        DigestRefreshVO result = service.refresh(NOW);

        assertThat(result.isRefreshed()).isTrue();
        assertThat(result.getMinutesSince()).isGreaterThan(60);
    }

    @Test
    void thresholdsComeFromSettings() {
        // 阈值被调成 10 条：40 条就该整理了
        stubSettings("1", LAST_RUN, "10", "60");
        when(chatSettingService.listEnabledGroupIds()).thenReturn(List.of(9L));
        when(messageLogMapper.selectCount(any())).thenReturn(40L);
        when(chatSettingService.listAll()).thenReturn(List.of(group(9L, "三班群", true)));
        when(chatDigestService.digestChat(9L, "三班群", LAST_RUN, NOW)).thenReturn(new DigestOutcome(1, false));

        assertThat(service.refresh(NOW).isRefreshed()).isTrue();
    }

    /**
     * 线上复现：窗口里有消息，但模型返回空（或 Dify 抖动），一条都没写。
     * 此时绝不能推锚点，否则这批消息永久消失，首页只剩「没什么事」。
     */
    @Test
    void emptyResultWithMessages_keepsTheAnchorForRetry() {
        stubSettings("1", LAST_RUN, "1", "60");
        when(chatSettingService.listEnabledGroupIds()).thenReturn(List.of(9L));
        when(messageLogMapper.selectCount(any())).thenReturn(4L);
        when(chatSettingService.listAll()).thenReturn(List.of(group(9L, "三班群", true)));
        when(chatDigestService.digestChat(9L, "三班群", LAST_RUN, NOW))
                .thenReturn(new DigestOutcome(0, false));

        DigestRefreshVO result = service.refresh(NOW);

        assertThat(result.isRefreshed()).isTrue();
        assertThat(result.getSaved()).isZero();
        verify(appSettingService, never()).putAll(any());
    }

    /**
     * 窗口里全是 666/哈哈哈：一条都没写，但这不是失败，消息已经「处理过」。
     * 锚点必须推进，否则每次进首页都会把同一批噪音重新过滤一遍。
     */
    @Test
    void allNoiseWindow_advancesTheAnchorWithoutRetrying() {
        stubSettings("1", LAST_RUN, "1", "60");
        when(chatSettingService.listEnabledGroupIds()).thenReturn(List.of(9L));
        when(messageLogMapper.selectCount(any())).thenReturn(4L);
        when(chatSettingService.listAll()).thenReturn(List.of(group(9L, "三班群", true)));
        when(chatDigestService.digestChat(9L, "三班群", LAST_RUN, NOW))
                .thenReturn(new DigestOutcome(0, true));

        DigestRefreshVO result = service.refresh(NOW);

        assertThat(result.isRefreshed()).isTrue();
        assertThat(result.getSaved()).isZero();
        verify(appSettingService).putAll(
                Map.of(DigestRefreshService.LAST_RUN_KEY, NOW.toString()));
    }

    @Test
    void failedGroup_keepsTheAnchorForRetry() {
        stubSettings("1", LAST_RUN, "1", "60");
        when(chatSettingService.listEnabledGroupIds()).thenReturn(List.of(9L));
        when(messageLogMapper.selectCount(any())).thenReturn(4L);
        when(chatSettingService.listAll()).thenReturn(List.of(group(9L, "三班群", true)));
        when(chatDigestService.digestChat(9L, "三班群", LAST_RUN, NOW))
                .thenThrow(new IllegalStateException("Dify 超时"));

        DigestRefreshVO result = service.refresh(NOW);

        assertThat(result.isRefreshed()).isTrue();
        assertThat(result.getSaved()).isZero();
        verify(appSettingService, never()).putAll(any());
    }

    @Test
    void masterSwitchOff_neverCallsTheModel() {
        stubSettings("0", LAST_RUN, "100", "60");
        when(chatSettingService.listEnabledGroupIds()).thenReturn(List.of(9L));
        when(messageLogMapper.selectCount(any())).thenReturn(500L);

        DigestRefreshVO result = service.refresh(NOW);

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.isRefreshed()).isFalse();
        verify(chatDigestService, never()).digestChat(any(), anyString(), any(), any());
        verify(appSettingService, never()).putAll(any());
    }

    private void stubSettings(String enabled, LocalDateTime lastRun, String minMessages, String minMinutes) {
        when(appSettingService.getOrDefault(eq("digest_enabled"), any())).thenReturn(enabled);
        when(appSettingService.getOrDefault(eq(DigestRefreshService.LAST_RUN_KEY), any()))
                .thenReturn(lastRun.toString());
        when(appSettingService.getOrDefault(eq(DigestRefreshService.MIN_MESSAGES_KEY), any()))
                .thenReturn(minMessages);
        // 消息条数先判，超了就不再看分钟数（短路），所以这条可能用不上
        lenient().when(appSettingService.getOrDefault(eq(DigestRefreshService.MIN_MINUTES_KEY), any()))
                .thenReturn(minMinutes);
    }

    private ChatSetting group(long chatId, String name, boolean digestEnabled) {
        ChatSetting setting = new ChatSetting();
        setting.setChatId(chatId);
        setting.setKind("GROUP");
        setting.setChatName(name);
        setting.setDigestEnabled(digestEnabled);
        return setting;
    }
}
