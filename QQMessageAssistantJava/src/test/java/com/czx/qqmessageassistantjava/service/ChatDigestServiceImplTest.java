package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.client.DifyWorkflowClient;
import com.czx.qqmessageassistantjava.entity.ChatDigestItem;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.ChatDigestItemMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.impl.ChatDigestServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatDigestServiceImplTest {

    private static final Long CHAT_ID = 9L;
    private static final String CHAT_NAME = "三班群";
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 30, 14, 0);
    private static final LocalDateTime END = START.plusHours(1);

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
        service = new ChatDigestServiceImpl(
                messageLogMapper, digestItemMapper, dify, appSettingService, chatSettingService,
                noOpTemplate());
    }

    /** 不发真事务，但走 TransactionTemplate 的真实提交/回滚路径。 */
    private static TransactionTemplate noOpTemplate() {
        return new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        });
    }

    @Test
    void digestChat_dropsItemsWhoseEvidenceIdsAreUnknown() {
        givenMessages(
                msg(1L, "明天下午两点开班会", "班主任", START.plusMinutes(3)),
                msg(2L, "收到", "小明", START.plusMinutes(4)));
        when(appSettingService.getOrDefault(any(), any())).thenReturn("");
        when(dify.run(eq("digest"), anyMap())).thenReturn(Map.of("items", """
                [{"summary":"班主任提醒明天下午开班会","importance":4,"keywords":"班会","mentions_me":false,"source_msg_ids":[1]},
                 {"summary":"小明说收到","importance":2,"keywords":"","mentions_me":false,"source_msg_ids":[2,999]}]
                """));

        int saved = service.digestChat(CHAT_ID, CHAT_NAME, START, END);

        assertThat(saved).isEqualTo(1);
        verify(digestItemMapper, times(1)).insert(any(ChatDigestItem.class));
    }

    @Test
    void digestChat_skipsDifyWhenWindowIsEmpty() {
        givenMessages();

        assertThat(service.digestChat(CHAT_ID, CHAT_NAME, START, END)).isZero();
        verifyNoInteractions(dify, digestItemMapper);
    }

    @Test
    void digestChat_truncatesToTwoHundredNewestMessages() {
        List<MessageLog> rows = new ArrayList<>();
        for (long id = 1; id <= 260; id++) {
            rows.add(msg(id, "第" + id + "条", "同学", START.plusSeconds(id)));
        }
        givenMessages(rows.toArray(new MessageLog[0]));
        when(appSettingService.getOrDefault(any(), any())).thenReturn("");
        when(dify.run(eq("digest"), anyMap())).thenReturn(Map.of("items", "[]"));

        service.digestChat(CHAT_ID, CHAT_NAME, START, END);

        String messages = capturedMessages();
        String[] lines = messages.split("\n");
        assertThat(lines).hasSize(200);
        assertThat(lines[0]).startsWith("61|");
        assertThat(lines[199]).startsWith("260|");
    }

    @Test
    void digestChat_clampsImportanceToOneThroughFive() {
        givenMessages(msg(1L, "改天再约", "小明", START.plusMinutes(1)));
        when(appSettingService.getOrDefault(any(), any())).thenReturn("");
        when(dify.run(eq("digest"), anyMap())).thenReturn(Map.of("items", """
                [{"summary":"改天再约","importance":9,"keywords":"","mentions_me":false,"source_msg_ids":[1]}]
                """));

        service.digestChat(CHAT_ID, CHAT_NAME, START, END);

        ArgumentCaptor<ChatDigestItem> captor = ArgumentCaptor.forClass(ChatDigestItem.class);
        verify(digestItemMapper).insert(captor.capture());
        assertThat(captor.getValue().getImportance()).isEqualTo(5);
    }

    @Test
    void digestChat_marksMessagesThatMentionTheOwner() {
        givenMessages(
                msg(1L, "明哥你明天来吗", "小李", START.plusMinutes(1)),
                msg(2L, "我先睡了", "小王", START.plusMinutes(2)));
        when(appSettingService.getOrDefault(eq("owner_aliases"), any())).thenReturn("小明,明哥");
        when(appSettingService.getOrDefault(eq("digest_focus_prompt"), any())).thenReturn("");
        when(dify.run(eq("digest"), anyMap())).thenReturn(Map.of("items", "[]"));

        service.digestChat(CHAT_ID, CHAT_NAME, START, END);

        String[] lines = capturedMessages().split("\n");
        assertThat(lines[0]).contains("[@我]");
        assertThat(lines[1]).doesNotContain("[@我]");
    }

    @Test
    void digestChat_rollsBackTheDeleteWhenAnInsertFails() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(status);
        ChatDigestServiceImpl transactional = new ChatDigestServiceImpl(
                messageLogMapper, digestItemMapper, dify, appSettingService, chatSettingService,
                new TransactionTemplate(txManager));

        givenMessages(
                msg(1L, "十点开会", "小明", START.plusMinutes(1)),
                msg(2L, "带材料", "小李", START.plusMinutes(2)));
        when(appSettingService.getOrDefault(any(), any())).thenReturn("");
        when(dify.run(eq("digest"), anyMap())).thenReturn(Map.of("items", """
                [{"summary":"十点开会","importance":4,"keywords":"","mentions_me":false,"source_msg_ids":[1]},
                 {"summary":"要带材料","importance":3,"keywords":"","mentions_me":false,"source_msg_ids":[2]}]
                """));
        doThrow(new RuntimeException("第二条插不进去"))
                .when(digestItemMapper).insert(any(ChatDigestItem.class));

        assertThatThrownBy(() -> transactional.digestChat(CHAT_ID, CHAT_NAME, START, END))
                .hasMessageContaining("第二条插不进去");

        // 删已经发出去、插却失败：必须回滚，否则这一小时永远空着（调度器不会回来补）
        verify(txManager).rollback(status);
    }

    private void givenMessages(MessageLog... rows) {
        when(messageLogMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(rows)));
    }

    private String capturedMessages() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(dify).run(eq("digest"), captor.capture());
        return (String) captor.getValue().get("messages");
    }

    private MessageLog msg(long id, String content, String senderName, LocalDateTime createdAt) {
        MessageLog log = new MessageLog();
        log.setId(id);
        log.setChatId(CHAT_ID);
        log.setSenderId(1000L + id);
        log.setSenderName(senderName);
        log.setDirection("IN");
        log.setMsgType("text");
        log.setContent(content);
        log.setCreatedAt(createdAt);
        return log;
    }
}
