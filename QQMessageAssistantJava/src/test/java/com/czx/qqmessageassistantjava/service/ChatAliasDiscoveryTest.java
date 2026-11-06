package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.dto.MessageLogItem;
import com.czx.qqmessageassistantjava.entity.ChatAlias;
import com.czx.qqmessageassistantjava.entity.Draft;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.ChatAliasMapper;
import com.czx.qqmessageassistantjava.mapper.DraftMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.impl.ChatAliasServiceImpl;
import com.czx.qqmessageassistantjava.service.impl.MessageLogServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatAliasDiscoveryTest {

    @Mock
    ChatAliasMapper chatAliasMapper;

    @Mock
    DraftMapper draftMapper;

    @Mock
    MessageLogMapper messageLogMapper;

    @Mock
    ChatAliasService chatAliasService;

    private MessageLogServiceImpl messageLogService;

    private ChatAliasServiceImpl aliasService;

    @BeforeEach
    void setUp() {
        aliasService = new ChatAliasServiceImpl(chatAliasMapper, draftMapper, messageLogMapper);
        messageLogService = new MessageLogServiceImpl();
        // baseMapper 是 ServiceImpl 的父类字段，Mockito 的 @InjectMocks 注不进去
        ReflectionTestUtils.setField(messageLogService, "baseMapper", messageLogMapper);
        ReflectionTestUtils.setField(messageLogService, "chatAliasService", chatAliasService);
    }

    @Test
    void groupMessageFromNewChatCreatesOneNamingDraft() {
        givenNoAlias(2002L, "GROUP");
        givenNoNamingDraft();

        aliasService.ensureNamingDraft(2002L, "GROUP", "三班群", 1001L);

        Draft draft = capturedDraft();
        assertThat(draft.getType()).isEqualTo("NAMING");
        assertThat(draft.getStatus()).isEqualTo("PENDING");
        assertThat(draft.getChatId()).isEqualTo(2002L);
        assertThat(draft.getSenderId()).isEqualTo(1001L);
        assertThat(draft.getTitle()).contains("2002");
        assertThat(draft.getPayload())
                .containsEntry("chatId", 2002L)
                .containsEntry("kind", "GROUP")
                .containsEntry("suggestedName", "三班群");
    }

    @Test
    void privateMessageUsesPeerIdAsChatKeyAndNullDraftChatId() {
        givenNoAlias(777L, "PRIVATE");
        givenNoNamingDraft();

        aliasService.ensureNamingDraft(777L, "PRIVATE", "阿黄", 777L);

        Draft draft = capturedDraft();
        assertThat(draft.getChatId()).isNull();
        assertThat(draft.getPayload())
                .containsEntry("chatId", 777L)
                .containsEntry("kind", "PRIVATE");
        assertThat(draft.getTitle()).contains("777");
    }

    @Test
    void existingAliasRowSkipsDraft() {
        when(chatAliasMapper.find(2002L, "GROUP")).thenReturn(aliasRow(2002L, "GROUP", "三班群"));

        aliasService.ensureNamingDraft(2002L, "GROUP", "三班群", 1001L);

        verify(draftMapper, never()).insert(any(Draft.class));
    }

    @Test
    void existingNamingDraftSkipsSecondOne() {
        givenNoAlias(2002L, "GROUP");
        when(draftMapper.selectList(any())).thenReturn(List.of(namingDraft(2002L, "GROUP")));

        aliasService.ensureNamingDraft(2002L, "GROUP", "三班群", 1001L);

        verify(draftMapper, never()).insert(any(Draft.class));
    }

    @Test
    void sameNumberDifferentKindDoesNotCollide() {
        when(chatAliasMapper.find(123L, "GROUP")).thenReturn(null);
        // 同号但已经是某个人的私聊草稿：群聊草稿仍要建
        when(draftMapper.selectList(any())).thenReturn(List.of(namingDraft(123L, "PRIVATE")));

        aliasService.ensureNamingDraft(123L, "GROUP", "三班群", 1001L);

        Draft draft = capturedDraft();
        assertThat(draft.getPayload()).containsEntry("kind", "GROUP");
    }

    @Test
    void backfillCreatesDraftsForSeenGroupAndPrivatePeers() {
        when(messageLogMapper.selectGroupPeers()).thenReturn(List.of(
                peer(2002L, 1001L, "三班群", "IN")));
        when(messageLogMapper.selectPrivatePeers()).thenReturn(List.of(
                peer(null, 777L, "阿黄", "IN")));
        givenNoAlias(2002L, "GROUP");
        givenNoAlias(777L, "PRIVATE");
        givenNoNamingDraft();

        int created = aliasService.backfillNamingDrafts();

        assertThat(created).isEqualTo(2);
        verify(draftMapper, times(2)).insert(any(Draft.class));
    }

    @Test
    void backfillSkipsChatsThatAlreadyHaveAliasOrDraft() {
        when(messageLogMapper.selectGroupPeers()).thenReturn(List.of(
                peer(2002L, null, "三班群", "IN")));
        when(messageLogMapper.selectPrivatePeers()).thenReturn(List.of(
                peer(null, 777L, "阿黄", "IN")));
        when(chatAliasMapper.find(2002L, "GROUP")).thenReturn(aliasRow(2002L, "GROUP", "三班群"));
        when(chatAliasMapper.find(777L, "PRIVATE")).thenReturn(null);
        when(draftMapper.selectList(any())).thenReturn(List.of(namingDraft(777L, "PRIVATE")));

        assertThat(aliasService.backfillNamingDrafts()).isZero();
        verify(draftMapper, never()).insert(any(Draft.class));
    }

    @Test
    void backfillNeverInsertsNullSenderId() {
        // draft.sender_id 是 NOT NULL：群聊补发一旦塞 null，启动补发会整批失败
        when(messageLogMapper.selectGroupPeers()).thenReturn(List.of(
                peer(2002L, null, "三班群", "IN")));
        when(messageLogMapper.selectPrivatePeers()).thenReturn(List.of());
        givenNoAlias(2002L, "GROUP");
        givenNoNamingDraft();

        aliasService.backfillNamingDrafts();

        assertThat(capturedDraft().getSenderId()).isNotNull();
    }

    @Test
    void inboundMessageTriggersNamingDraftWithGroupKey() {
        when(messageLogMapper.insert(any(MessageLog.class))).thenReturn(1);

        int saved = messageLogService.batchSave(List.of(item(2002L, 1001L, "IN")));

        assertThat(saved).isEqualTo(1);
        verify(chatAliasService).ensureNamingDraft(eq(2002L), eq("GROUP"),
                anyString(), eq(1001L));
    }

    @Test
    void inboundPrivateMessageTriggersNamingDraftWithPeerKey() {
        when(messageLogMapper.insert(any(MessageLog.class))).thenReturn(1);

        messageLogService.batchSave(List.of(item(null, 777L, "IN")));

        verify(chatAliasService).ensureNamingDraft(eq(777L), eq("PRIVATE"),
                anyString(), eq(777L));
    }

    @Test
    void outboundMessageDoesNotCreateNamingDraft() {
        when(messageLogMapper.insert(any(MessageLog.class))).thenReturn(1);

        messageLogService.batchSave(List.of(item(2002L, 1001L, "OUT")));

        verify(chatAliasService, never()).ensureNamingDraft(anyLong(), anyString(),
                anyString(), anyLong());
    }

    @Test
    void draftFailureDoesNotBreakMessageSaving() {
        when(messageLogMapper.insert(any(MessageLog.class))).thenReturn(1);
        doThrow(new RuntimeException("draft boom"))
                .when(chatAliasService).ensureNamingDraft(anyLong(), anyString(),
                        anyString(), anyLong());

        assertThat(messageLogService.batchSave(List.of(item(2002L, 1001L, "IN")))).isEqualTo(1);
    }

    private void givenNoAlias(long chatId, String kind) {
        when(chatAliasMapper.find(chatId, kind)).thenReturn(null);
    }

    private void givenNoNamingDraft() {
        when(draftMapper.selectList(any())).thenReturn(List.of());
    }

    private Draft capturedDraft() {
        ArgumentCaptor<Draft> captor = ArgumentCaptor.forClass(Draft.class);
        verify(draftMapper).insert(captor.capture());
        return captor.getValue();
    }

    private ChatAlias aliasRow(long chatId, String kind, String aliases) {
        ChatAlias row = new ChatAlias();
        row.setChatId(chatId);
        row.setKind(kind);
        row.setAliases(aliases);
        return row;
    }

    private Draft namingDraft(long chatId, String kind) {
        Draft draft = new Draft();
        draft.setType("NAMING");
        draft.setStatus("PENDING");
        draft.setPayload(Map.of("chatId", chatId, "kind", kind));
        return draft;
    }

    private MessageLog peer(Long chatId, Long senderId, String name, String direction) {
        MessageLog row = new MessageLog();
        row.setChatId(chatId);
        row.setSenderId(senderId);
        row.setChatName(name);
        row.setDirection(direction);
        return row;
    }

    private MessageLogItem item(Long chatId, Long senderId, String direction) {
        MessageLogItem item = new MessageLogItem();
        item.setChatId(chatId);
        item.setSenderId(senderId);
        item.setSenderName("小张");
        item.setChatName(chatId == null ? null : "三班群");
        item.setDirection(direction);
        item.setMsgType("text");
        item.setContent("明天见");
        return item;
    }
}
