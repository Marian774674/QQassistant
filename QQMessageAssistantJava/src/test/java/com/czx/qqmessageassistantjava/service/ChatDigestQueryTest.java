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

    private ChatDigestServiceImpl service;

    @BeforeEach
    void setUp() {
        // 这组测试只读，事务模板用不上；给个不发事务的空实现
        service = new ChatDigestServiceImpl(
                messageLogMapper, digestItemMapper, dify, appSettingService, chatSettingService,
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
                        }));
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
        item.setSourceMsgIds("1,2");
        item.setPeriodEnd(createdAt);
        item.setCreatedAt(createdAt);
        return item;
    }
}
