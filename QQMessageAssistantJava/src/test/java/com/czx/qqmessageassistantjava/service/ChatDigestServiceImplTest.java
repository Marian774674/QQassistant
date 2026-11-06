package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.client.DifyWorkflowClient;
import com.czx.qqmessageassistantjava.common.BusinessException;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @Mock
    ChatAliasService chatAliasService;

    @Mock
    MessageNoiseFilter noiseFilter;

    private ChatDigestServiceImpl service;

    @BeforeEach
    void setUp() {
        // 默认全部保留；个别测试再覆盖成「这批全是噪音」
        lenient().when(noiseFilter.filter(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MessageLog> rows = invocation.getArgument(0);
            return new MessageNoiseFilter.FilterOutcome(rows, 0, Map.of());
        });
        // 5.5：整理的两个阈值改成从 app_setting 读取（intSetting）。给一个宽松兜底，
        // 返回空串让 intSetting 回落到出厂值；个别用例再按具体键覆盖。
        // 用 lenient 是因为「窗口为空」这类用例根本不会读配置。
        lenient().when(appSettingService.getOrDefault(any(), any())).thenReturn("");
        service = new ChatDigestServiceImpl(
                messageLogMapper, digestItemMapper, dify, appSettingService, chatSettingService,
                chatAliasService, noOpTemplate(), noiseFilter);
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
        when(dify.run(eq("digest"), anyMap(), eq(CHAT_ID))).thenReturn(Map.of("items", """
                [{"summary":"班主任提醒明天下午开班会","importance":4,"keywords":"班会","mentions_me":false,"source_msg_ids":[1]},
                 {"summary":"小明说收到","importance":2,"keywords":"","mentions_me":false,"source_msg_ids":[2,999]}]
                """));

        int saved = service.digestChat(CHAT_ID, CHAT_NAME, START, END).saved();

        assertThat(saved).isEqualTo(1);
        verify(digestItemMapper, times(1)).insert(any(ChatDigestItem.class));
    }

    @Test
    void digestChat_skipsDifyWhenWindowIsEmpty() {
        givenMessages();

        assertThat(service.digestChat(CHAT_ID, CHAT_NAME, START, END).saved()).isZero();
        verifyNoInteractions(dify, digestItemMapper);
    }

    @Test
    void digestChat_skipsDifyAndReportsNoiseOnlyWhenEveryMessageIsFiltered() {
        givenMessages(
                msg(1L, "666", "小明", START.plusMinutes(1)),
                msg(2L, "哈哈哈", "小李", START.plusMinutes(2)));
        when(noiseFilter.filter(anyList())).thenReturn(
                new MessageNoiseFilter.FilterOutcome(List.of(), 2, Map.of("keyword", 2)));

        ChatDigestService.DigestOutcome outcome =
                service.digestChat(CHAT_ID, CHAT_NAME, START, END);

        assertThat(outcome.saved()).isZero();
        assertThat(outcome.noiseOnly()).isTrue();
        verifyNoInteractions(dify, digestItemMapper);
    }

    @Test
    void digestChat_filtersNoiseBeforeApplyingTheTwoHundredMessageCap() {
        // 共 300 条：最新的 100 条是噪音，较早的 200 条才是有内容的。
        // 若先截断再过滤，送模型的 200 条里会混进 100 条噪音；正确顺序应当是过滤后正好 200 条。
        List<MessageLog> rows = new ArrayList<>();
        for (long id = 1; id <= 200; id++) {
            rows.add(msg(id, "第" + id + "条", "同学", START.plusSeconds(id)));
        }
        for (long id = 201; id <= 300; id++) {
            rows.add(msg(id, "666", "同学", START.plusSeconds(id)));
        }
        givenMessages(rows.toArray(new MessageLog[0]));
        when(noiseFilter.filter(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MessageLog> all = invocation.getArgument(0);
            List<MessageLog> kept = all.stream()
                    .filter(message -> message.getId() <= 200)
                    .toList();
            return new MessageNoiseFilter.FilterOutcome(kept, 100, Map.of("keyword", 100));
        });
        when(appSettingService.getOrDefault(any(), any())).thenReturn("");
        when(dify.run(eq("digest"), anyMap(), eq(CHAT_ID))).thenReturn(Map.of("items", "[]"));

        service.digestChat(CHAT_ID, CHAT_NAME, START, END);

        String[] lines = capturedMessages().split("\n");
        assertThat(lines).hasSize(200);
        assertThat(lines[0]).startsWith("1|");
        assertThat(lines[199]).startsWith("200|");
    }

    @Test
    void digestChat_truncatesToTwoHundredNewestMessages() {
        List<MessageLog> rows = new ArrayList<>();
        for (long id = 1; id <= 260; id++) {
            rows.add(msg(id, "第" + id + "条", "同学", START.plusSeconds(id)));
        }
        givenMessages(rows.toArray(new MessageLog[0]));
        when(appSettingService.getOrDefault(any(), any())).thenReturn("");
        when(dify.run(eq("digest"), anyMap(), eq(CHAT_ID))).thenReturn(Map.of("items", "[]"));

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
        when(dify.run(eq("digest"), anyMap(), eq(CHAT_ID))).thenReturn(Map.of("items", """
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
        when(appSettingService.getOrDefault(eq("owner_sender_id"), any())).thenReturn("");
        when(appSettingService.getOrDefault(eq("digest_focus_prompt"), any())).thenReturn("");
        when(dify.run(eq("digest"), anyMap(), eq(CHAT_ID))).thenReturn(Map.of("items", "[]"));

        service.digestChat(CHAT_ID, CHAT_NAME, START, END);

        String[] lines = capturedMessages().split("\n");
        assertThat(lines[0]).contains("[@我]");
        assertThat(lines[1]).doesNotContain("[@我]");
    }

    /**
     * 线上真实复现：同一批非空消息，模型会间歇性返回 []（节点输出就是 "[]"，不是解析失败）。
     * 第一次空结果必须重试，不能把这一窗口当成「没事发生」。
     */
    @Test
    void digestChat_retriesOnceWhenModelReturnsNoItemsForNonEmptyWindow() {
        givenMessages(msg(1L, "求11.8蛟桥体测代跑", "小张", START.plusMinutes(5)));
        when(appSettingService.getOrDefault(any(), any())).thenReturn("");
        when(dify.run(eq("digest"), anyMap(), eq(CHAT_ID)))
                .thenReturn(Map.of("items", "[]"))
                .thenReturn(Map.of("items", """
                        [{"summary":"小张求体测代跑","importance":2,"keywords":"体测","mentions_me":false,"source_msg_ids":[1]}]
                        """));

        int saved = service.digestChat(CHAT_ID, CHAT_NAME, START, END).saved();

        assertThat(saved).isEqualTo(1);
        verify(dify, times(2)).run(eq("digest"), anyMap(), eq(CHAT_ID));
    }

    /** 重试仍为空时也要落一条兜底，否则首页会把「有消息」显示成「没什么事」。 */
    @Test
    void digestChat_writesFallbackWhenTheRetryAlsoReturnsNothing() {
        givenMessages(
                msg(1L, "代刷各平台网课", "广告号", START.plusMinutes(1)),
                msg(2L, "求3.55一教代签", "小刘", START.plusMinutes(2)));
        when(appSettingService.getOrDefault(any(), any())).thenReturn("");
        when(dify.run(eq("digest"), anyMap(), eq(CHAT_ID))).thenReturn(Map.of("items", "[]"));

        int saved = service.digestChat(CHAT_ID, CHAT_NAME, START, END).saved();

        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<ChatDigestItem> captor = ArgumentCaptor.forClass(ChatDigestItem.class);
        verify(digestItemMapper).insert(captor.capture());
        ChatDigestItem row = captor.getValue();
        assertThat(row.getSummary()).contains("2 条消息");
        assertThat(row.getImportance()).isEqualTo(1);
        assertThat(row.getSourceMsgIds()).isEqualTo("1,2");
        assertThat(row.getMentionsMe()).isFalse();
    }

    @Test
    void digestChat_fallbackIsImportantWhenTheWindowMentionsTheOwner() {
        givenMessages(msg(1L, "@1753800374 明天有空吗", "小张", START.plusMinutes(1)));
        when(appSettingService.getOrDefault(eq("owner_aliases"), any())).thenReturn("");
        when(appSettingService.getOrDefault(eq("owner_sender_id"), any())).thenReturn("1753800374");
        when(appSettingService.getOrDefault(eq("digest_focus_prompt"), any())).thenReturn("");
        when(dify.run(eq("digest"), anyMap(), eq(CHAT_ID))).thenReturn(Map.of("items", "[]"));

        service.digestChat(CHAT_ID, CHAT_NAME, START, END);

        ArgumentCaptor<ChatDigestItem> captor = ArgumentCaptor.forClass(ChatDigestItem.class);
        verify(digestItemMapper).insert(captor.capture());
        assertThat(captor.getValue().getMentionsMe()).isTrue();
        assertThat(captor.getValue().getImportance()).isEqualTo(4);
    }

    /** 群里 @ 的是 QQ 号而不是昵称时，模型不一定标 mentions_me；Java 按证据行兜底。 */
    @Test
    void digestChat_marksItemAsMentionWhenEvidenceContainsOwnerSenderId() {
        givenMessages(msg(1L, "@1753800374 明天有空吗", "小张", START.plusMinutes(1)));
        when(appSettingService.getOrDefault(eq("owner_aliases"), any())).thenReturn("");
        when(appSettingService.getOrDefault(eq("owner_sender_id"), any())).thenReturn("1753800374");
        when(appSettingService.getOrDefault(eq("digest_focus_prompt"), any())).thenReturn("");
        when(dify.run(eq("digest"), anyMap(), eq(CHAT_ID))).thenReturn(Map.of("items", """
                [{"summary":"小张问明天是否有空","importance":2,"keywords":"","mentions_me":false,"source_msg_ids":[1]}]
                """));

        service.digestChat(CHAT_ID, CHAT_NAME, START, END);

        ArgumentCaptor<ChatDigestItem> captor = ArgumentCaptor.forClass(ChatDigestItem.class);
        verify(digestItemMapper).insert(captor.capture());
        assertThat(captor.getValue().getMentionsMe()).isTrue();
        assertThat(captor.getValue().getImportance()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void digestChat_failsLoudlyWhenItemsStayUnparseable() {
        givenMessages(msg(1L, "十点开会", "小明", START.plusMinutes(1)));
        when(appSettingService.getOrDefault(any(), any())).thenReturn("");
        when(dify.run(eq("digest"), anyMap(), eq(CHAT_ID))).thenReturn(Map.of("items", "not-json"));

        assertThatThrownBy(() -> service.digestChat(CHAT_ID, CHAT_NAME, START, END))
                .isInstanceOf(BusinessException.class);
        verify(digestItemMapper, never()).delete(any());
    }

    @Test
    void digestChat_rollsBackTheDeleteWhenAnInsertFails() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(status);
        ChatDigestServiceImpl transactional = new ChatDigestServiceImpl(
                messageLogMapper, digestItemMapper, dify, appSettingService, chatSettingService,
                chatAliasService, new TransactionTemplate(txManager), noiseFilter);

        givenMessages(
                msg(1L, "十点开会", "小明", START.plusMinutes(1)),
                msg(2L, "带材料", "小李", START.plusMinutes(2)));
        when(appSettingService.getOrDefault(any(), any())).thenReturn("");
        when(dify.run(eq("digest"), anyMap(), eq(CHAT_ID))).thenReturn(Map.of("items", """
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

    /** 5.5：Token 账本按会话分账，整理调用必须把 chatId 传到 DifyWorkflowClient。 */
    @Test
    void digestChat_passesChatIdToTheWorkflowClient() {
        givenMessages(msg(1L, "十点开会", "小明", START.plusMinutes(1)));
        when(dify.run(eq("digest"), anyMap(), eq(CHAT_ID))).thenReturn(Map.of("items", "[]"));

        service.digestChat(CHAT_ID, CHAT_NAME, START, END);

        ArgumentCaptor<Long> chatIds = ArgumentCaptor.forClass(Long.class);
        verify(dify, atLeastOnce()).run(eq("digest"), anyMap(), chatIds.capture());
        assertThat(chatIds.getAllValues()).containsOnly(CHAT_ID);
    }

    /** 5.5：阈值收口后可在 app_setting 覆盖；填 50 就只送最近 50 条。 */
    @Test
    void digestChat_honoursConfiguredMaxMessages() {
        List<MessageLog> rows = new ArrayList<>();
        for (long id = 1; id <= 60; id++) {
            rows.add(msg(id, "第" + id + "条", "同学", START.plusSeconds(id)));
        }
        givenMessages(rows.toArray(new MessageLog[0]));
        when(appSettingService.getOrDefault(eq("digest_max_messages"), any())).thenReturn("50");
        when(dify.run(eq("digest"), anyMap(), eq(CHAT_ID))).thenReturn(Map.of("items", "[]"));

        service.digestChat(CHAT_ID, CHAT_NAME, START, END);

        assertThat(capturedMessages().split("\n")).hasSize(50);
    }

    /** 5.5：写坏的阈值不能让整理停摆，必须回落到出厂的 200。 */
    @Test
    void digestChat_fallsBackToFactoryDefaultWhenThresholdIsGarbage() {
        List<MessageLog> rows = new ArrayList<>();
        for (long id = 1; id <= 260; id++) {
            rows.add(msg(id, "第" + id + "条", "同学", START.plusSeconds(id)));
        }
        givenMessages(rows.toArray(new MessageLog[0]));
        when(appSettingService.getOrDefault(eq("digest_max_messages"), any())).thenReturn("不是数字");
        when(dify.run(eq("digest"), anyMap(), eq(CHAT_ID))).thenReturn(Map.of("items", "[]"));

        service.digestChat(CHAT_ID, CHAT_NAME, START, END);

        assertThat(capturedMessages().split("\n")).hasSize(200);
    }

    /** 5.5：空结果重试次数也可配；配成 1 就只调一次，不再白烧一次模型。 */
    @Test
    void digestChat_honoursConfiguredMaxAttempts() {
        givenMessages(msg(1L, "十点开会", "小明", START.plusMinutes(1)));
        when(appSettingService.getOrDefault(eq("digest_max_attempts"), any())).thenReturn("1");
        when(dify.run(eq("digest"), anyMap(), eq(CHAT_ID))).thenReturn(Map.of("items", "[]"));

        service.digestChat(CHAT_ID, CHAT_NAME, START, END);

        verify(dify, times(1)).run(eq("digest"), anyMap(), eq(CHAT_ID));
    }

    private void givenMessages(MessageLog... rows) {
        when(messageLogMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(rows)));
    }

    private String capturedMessages() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        // 模型返回空时会重试一次，同一批 messages 会被送两遍
        verify(dify, atLeastOnce()).run(eq("digest"), captor.capture(), eq(CHAT_ID));
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
