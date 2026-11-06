package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.client.DifyWorkflowClient;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.entity.StyleProfile;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.impl.StyleSummaryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StyleSummaryServiceTest {

    private static final long OWNER = 1001L;

    @Mock
    StyleProfileService styleProfileService;
    @Mock
    MessageLogMapper messageLogMapper;
    @Mock
    DifyWorkflowClient difyWorkflowClient;
    @Mock
    AppSettingService appSettingService;

    private final Map<String, String> settings = new HashMap<>();
    private StyleSummaryServiceImpl service;

    @BeforeEach
    void setUp() {
        settings.clear();
        service = new StyleSummaryServiceImpl(
                styleProfileService, messageLogMapper, difyWorkflowClient, appSettingService);
        lenient().when(appSettingService.getOrDefault(anyString(), anyString()))
                .thenAnswer(inv -> settings.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
    }

    private void ownerBound() {
        settings.put("owner_sender_id", String.valueOf(OWNER));
    }

    private static StyleProfile profileWithLast(LocalDateTime last) {
        StyleProfile p = new StyleProfile();
        p.setSenderId(OWNER);
        p.setProfilePrompt("旧画像");
        p.setLastSummarizedAt(last);
        return p;
    }

    private static MessageLog msg(String content) {
        MessageLog m = new MessageLog();
        m.setId(1L);
        m.setSenderId(OWNER);
        m.setDirection("IN");
        m.setContent(content);
        return m;
    }

    // ---- 到期判定 ----

    @Test
    void notDueWhenOwnerNotBound() {
        assertThat(service.isDue()).isFalse();
        verifyNoInteractions(styleProfileService);
    }

    @Test
    void dueWhenNeverSummarized() {
        ownerBound();
        when(styleProfileService.getBySenderId(OWNER)).thenReturn(null);
        assertThat(service.isDue()).isTrue();
    }

    @Test
    void notDueWhenAlreadySummarizedToday() {
        ownerBound();
        when(styleProfileService.getBySenderId(OWNER))
                .thenReturn(profileWithLast(LocalDateTime.now()));
        assertThat(service.isDue()).isFalse();
    }

    @Test
    void dueWhenLastSummaryWasYesterday() {
        ownerBound();
        when(styleProfileService.getBySenderId(OWNER))
                .thenReturn(profileWithLast(LocalDateTime.now().minusDays(1)));
        assertThat(service.isDue()).isTrue();
    }

    @Test
    void weeklyNotDueWithinSevenDays() {
        settings.put("owner_sender_id", String.valueOf(OWNER));
        settings.put("summary_frequency", "WEEKLY");
        when(styleProfileService.getBySenderId(OWNER))
                .thenReturn(profileWithLast(LocalDateTime.now().minusDays(6)));
        assertThat(service.isDue()).isFalse();
    }

    @Test
    void weeklyDueAfterSevenDays() {
        settings.put("owner_sender_id", String.valueOf(OWNER));
        settings.put("summary_frequency", "WEEKLY");
        when(styleProfileService.getBySenderId(OWNER))
                .thenReturn(profileWithLast(LocalDateTime.now().minusDays(8)));
        assertThat(service.isDue()).isTrue();
    }

    // ---- 总结执行 ----

    @Test
    void summarizeSkipsWithoutOwner() {
        assertThat(service.summarize()).isFalse();
        verifyNoInteractions(difyWorkflowClient);
    }

    @Test
    void summarizeSkipsWhenNoOwnerMessages() {
        ownerBound();
        when(messageLogMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.summarize()).isFalse();
        verifyNoInteractions(difyWorkflowClient);
        verify(styleProfileService, never()).upsert(any(), anyString());
    }

    @Test
    void summarizeKeepsOldProfileWhenDifyFails() {
        ownerBound();
        when(messageLogMapper.selectList(any())).thenReturn(List.of(msg("哈哈哈")));
        when(styleProfileService.getBySenderId(OWNER)).thenReturn(profileWithLast(null));
        when(difyWorkflowClient.run(eq("style"), any()))
                .thenThrow(new BusinessException("dify down"));
        assertThat(service.summarize()).isFalse();
        verify(styleProfileService, never()).upsert(any(), anyString());
    }

    @Test
    void summarizeKeepsOldProfileWhenOutputBlank() {
        ownerBound();
        when(messageLogMapper.selectList(any())).thenReturn(List.of(msg("哈哈哈")));
        when(styleProfileService.getBySenderId(OWNER)).thenReturn(profileWithLast(null));
        when(difyWorkflowClient.run(eq("style"), any())).thenReturn(Map.of("style_prompt", "   "));
        assertThat(service.summarize()).isFalse();
        verify(styleProfileService, never()).upsert(any(), anyString());
    }

    @Test
    void summarizeWritesProfileOnSuccess() {
        ownerBound();
        when(messageLogMapper.selectList(any())).thenReturn(List.of(msg("哈哈哈"), msg("行")));
        when(styleProfileService.getBySenderId(OWNER)).thenReturn(profileWithLast(null));
        when(difyWorkflowClient.run(eq("style"), any()))
                .thenReturn(Map.of("style_prompt", "你说话很短，常用「行」"));
        assertThat(service.summarize()).isTrue();
        verify(styleProfileService).upsert(OWNER, "你说话很短，常用「行」");
    }

    @Test
    void runIfDueDoesNothingWhenNotDue() {
        ownerBound();
        when(styleProfileService.getBySenderId(OWNER))
                .thenReturn(profileWithLast(LocalDateTime.now()));
        assertThat(service.runIfDue()).isFalse();
        verifyNoInteractions(difyWorkflowClient);
    }
}
