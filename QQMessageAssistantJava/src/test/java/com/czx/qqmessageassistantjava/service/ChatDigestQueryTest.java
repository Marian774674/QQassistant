package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.client.DifyWorkflowClient;
import com.czx.qqmessageassistantjava.entity.ChatDigestItem;
import com.czx.qqmessageassistantjava.mapper.ChatDigestItemMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.impl.ChatDigestServiceImpl;
import com.czx.qqmessageassistantjava.vo.DigestItemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatDigestQueryTest {

    private static final LocalDateTime SINCE = LocalDateTime.of(2026, 10, 30, 8, 0);

    @Mock
    MessageLogMapper messageLogMapper;

    @Mock
    ChatDigestItemMapper digestItemMapper;

    @Mock
    DifyWorkflowClient dify;

    @Mock
    AppSettingService appSettingService;

    @Mock
    ChatSettingService chatSettingService;

    @Mock
    ChatAliasService chatAliasService;

    @Mock
    MessageNoiseFilter noiseFilter;

    private ChatDigestServiceImpl service;

    @BeforeEach
    void setUp() {
        // 这组测试只读，事务模板用不上；给个不发事务的空实现
        service = new ChatDigestServiceImpl(
                messageLogMapper, digestItemMapper, dify, appSettingService, chatSettingService,
                chatAliasService,
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                            @Override
                            protected Object doGetTransaction() {
                                return new Object();
                            }

                            @Override
                            protected void doBegin(Object transaction,
                                                   org.springframework.transaction.TransactionDefinition definition) {
                            }

                            @Override
                            protected void doCommit(
                                    org.springframework.transaction.support.DefaultTransactionStatus status) {
                            }

                            @Override
                            protected void doRollback(
                                    org.springframework.transaction.support.DefaultTransactionStatus status) {
                            }
                        }),
                noiseFilter);
    }

    @Test
    void listSince_replacesChatNameWithAlias() {
        when(chatSettingService.listEnabledGroupIds()).thenReturn(List.of(9L));
        when(chatAliasService.displayNames()).thenReturn(Map.of("GROUP:9", "班群"));
        givenRows(row(1L, 9L, "三班群", "班主任提醒明天开班会", 4, SINCE.plusMinutes(10)));

        List<DigestItemVO> items = service.listSince(SINCE);

        assertThat(items).extracting(DigestItemVO::getChatName).containsExactly("班群");
    }

    @Test
    void listSince_excludesChatsTheUserDidNotEnable() {
        when(chatSettingService.listEnabledGroupIds()).thenReturn(List.of(9L));
        givenRows(
                row(1L, 9L, "三班群", "班主任提醒明天开班会", 4, SINCE.plusMinutes(10)),
                row(2L, 10L, "游戏群", "有人在聊新角色", 3, SINCE.plusMinutes(20)),
                row(3L, 11L, "私聊", "在吗", 2, SINCE.plusMinutes(30)));

        List<DigestItemVO> items = service.listSince(SINCE);

        assertThat(items).extracting(DigestItemVO::getId).containsExactly(1L);
    }

    @Test
    void listSince_sortsByImportanceThenTime() {
        when(chatSettingService.listEnabledGroupIds()).thenReturn(List.of(9L));
        givenRows(
                row(1L, 9L, "三班群", "低优先级早发生", 2, SINCE.plusMinutes(1)),
                row(2L, 9L, "三班群", "高优先级早发生", 5, SINCE.plusMinutes(5)),
                row(3L, 9L, "三班群", "高优先级后发生", 5, SINCE.plusMinutes(50)));

        List<DigestItemVO> items = service.listSince(SINCE);

        assertThat(items).extracting(DigestItemVO::getId).containsExactly(2L, 3L, 1L);
    }

    @Test
    void listSince_dropsTheRowWhoseEvidenceIsCoveredByAnother() {
        when(chatSettingService.listEnabledGroupIds()).thenReturn(List.of(9L));
        ChatDigestItem narrow = row(1L, 9L, "三班群", "整点任务", 2, SINCE.plusMinutes(10));
        narrow.setSourceMsgIds("198");
        ChatDigestItem wide = row(2L, 9L, "三班群", "补跑，覆盖 198 和 199", 3, SINCE.plusMinutes(20));
        wide.setSourceMsgIds("198,199");
        givenRows(narrow, wide);

        List<DigestItemVO> items = service.listSince(SINCE);

        assertThat(items).extracting(DigestItemVO::getId).containsExactly(2L);
    }

    @Test
    void listSince_keepsTheNewerRowWhenEvidenceIsIdentical() {
        when(chatSettingService.listEnabledGroupIds()).thenReturn(List.of(9L));
        ChatDigestItem older = row(1L, 9L, "三班群", "整点任务", 3, SINCE.plusMinutes(10));
        ChatDigestItem newer = row(2L, 9L, "三班群", "补跑", 2, SINCE.plusMinutes(20));
        older.setSourceMsgIds("198");
        newer.setSourceMsgIds("198");
        givenRows(older, newer);

        List<DigestItemVO> items = service.listSince(SINCE);

        assertThat(items).extracting(DigestItemVO::getId).containsExactly(2L);
    }

    @Test
    void listSince_returnsEmptyWithoutQueryingWhenNothingIsEnabled() {
        when(chatSettingService.listEnabledGroupIds()).thenReturn(List.of());

        assertThat(service.listSince(SINCE)).isEmpty();
        verify(digestItemMapper, never()).selectList(any());
    }

    private void givenRows(ChatDigestItem... rows) {
        when(digestItemMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(rows)));
    }

    private ChatDigestItem row(long id, long chatId, String chatName, String summary,
                               int importance, LocalDateTime createdAt) {
        ChatDigestItem item = new ChatDigestItem();
        item.setId(id);
        item.setChatId(chatId);
        item.setChatName(chatName);
        item.setSummary(summary);
        item.setImportance(importance);
        item.setMentionsMe(false);
        // 每条各用自己 id 当证据：默认互不覆盖，只有显式构造的用例才制造重叠
        item.setSourceMsgIds(String.valueOf(id));
        item.setPeriodEnd(createdAt);
        item.setCreatedAt(createdAt);
        return item;
    }
}
