package com.czx.qqmessageassistantjava.schedule;

import com.czx.qqmessageassistantjava.entity.ChatSetting;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import com.czx.qqmessageassistantjava.service.ChatDigestService;
import com.czx.qqmessageassistantjava.service.ChatSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DigestSchedulerTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 30, 14, 0);
    private static final LocalDateTime END = START.plusHours(1);

    @Mock
    ChatSettingService chatSettingService;

    @Mock
    ChatDigestService chatDigestService;

    @Mock
    AppSettingService appSettingService;

    private DigestScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DigestScheduler(chatSettingService, chatDigestService, appSettingService);
    }

    @Test
    void runOnce_isNoOpWhenDisabled() {
        when(appSettingService.getOrDefault("digest_enabled", "0")).thenReturn("0");

        assertThat(scheduler.runOnce(START, END)).isZero();
        verifyNoInteractions(chatDigestService);
        verify(chatSettingService, never()).listAll();
    }

    @Test
    void runOnce_onlyDigestsEnabledGroups() {
        when(appSettingService.getOrDefault("digest_enabled", "0")).thenReturn("1");
        when(chatSettingService.listAll()).thenReturn(List.of(
                group(9L, "三班群", true),
                group(10L, "游戏群", false),
                privateChat(88L, "小明", true)));
        when(chatDigestService.digestChat(eq(9L), eq("三班群"), eq(START), eq(END))).thenReturn(2);

        assertThat(scheduler.runOnce(START, END)).isEqualTo(2);

        verify(chatDigestService).digestChat(9L, "三班群", START, END);
        verify(chatDigestService, never()).digestChat(eq(10L), anyString(), any(), any());
        verify(chatDigestService, never()).digestChat(eq(88L), anyString(), any(), any());
    }

    @Test
    void runOnce_continuesAfterOneChatFails() {
        when(appSettingService.getOrDefault("digest_enabled", "0")).thenReturn("1");
        when(chatSettingService.listAll()).thenReturn(List.of(
                group(9L, "三班群", true),
                group(10L, "游戏群", true)));
        when(chatDigestService.digestChat(eq(9L), anyString(), any(), any()))
                .thenThrow(new RuntimeException("Dify 挂了"));
        when(chatDigestService.digestChat(eq(10L), anyString(), any(), any())).thenReturn(1);

        assertThat(scheduler.runOnce(START, END)).isEqualTo(1);
        verify(chatDigestService).digestChat(10L, "游戏群", START, END);
    }

    @Test
    void windowOf_coversThePreviousWholeHour() {
        LocalDateTime[] window = DigestScheduler.windowOf(LocalDateTime.of(2026, 10, 30, 14, 5, 0));

        assertThat(window[0]).isEqualTo(LocalDateTime.of(2026, 10, 30, 13, 0));
        assertThat(window[1]).isEqualTo(LocalDateTime.of(2026, 10, 30, 14, 0));
    }

    private ChatSetting group(long chatId, String name, boolean digestEnabled) {
        return setting(chatId, "GROUP", name, digestEnabled);
    }

    private ChatSetting privateChat(long chatId, String name, boolean digestEnabled) {
        return setting(chatId, "PRIVATE", name, digestEnabled);
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
}
