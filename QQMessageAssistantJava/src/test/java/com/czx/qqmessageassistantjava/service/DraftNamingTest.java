package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.client.LangBotClient;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.DraftConfirmRequest;
import com.czx.qqmessageassistantjava.entity.Draft;
import com.czx.qqmessageassistantjava.mapper.DraftMapper;
import com.czx.qqmessageassistantjava.service.impl.DraftServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DraftNamingTest {

    @Mock
    DraftMapper draftMapper;

    @Mock
    ChatAliasService chatAliasService;

    @Mock
    LangBotClient langBotClient;

    @Mock
    MemoService memoService;

    @Mock
    ProfileFactService profileFactService;

    @Mock
    FileTaskService fileTaskService;

    private DraftServiceImpl draftService;

    @BeforeEach
    void setUp() {
        draftService = new DraftServiceImpl();
        ReflectionTestUtils.setField(draftService, "baseMapper", draftMapper);
        ReflectionTestUtils.setField(draftService, "chatAliasService", chatAliasService);
        ReflectionTestUtils.setField(draftService, "langBotClient", langBotClient);
        ReflectionTestUtils.setField(draftService, "memoService", memoService);
        ReflectionTestUtils.setField(draftService, "profileFactService", profileFactService);
        ReflectionTestUtils.setField(draftService, "fileTaskService", fileTaskService);
    }

    @Test
    void confirmNamingDraftSavesAliasesAndConfirms() {
        Draft draft = namingDraft(1L, 777L, "PRIVATE");
        when(draftMapper.selectById(1L)).thenReturn(draft);

        Draft confirmed = confirmNaming(1L, "阿黄,黄哥");

        verify(chatAliasService).saveAliases(777L, "PRIVATE", "阿黄,黄哥");
        assertThat(confirmed.getStatus()).isEqualTo("CONFIRMED");
        assertThat(confirmed.getConfirmedAt()).isNotNull();
        assertThat(confirmed.getPayload()).containsEntry("aliases", "阿黄,黄哥");
        verify(draftMapper).updateById(any(Draft.class));
        // 命名不是发送，不能碰 LangBot
        verify(langBotClient, never()).sendMessage(any());
    }

    @Test
    void confirmNamingDraftRejectsBlankAliases() {
        Draft draft = namingDraft(1L, 777L, "PRIVATE");
        when(draftMapper.selectById(1L)).thenReturn(draft);

        assertThatThrownBy(() -> confirmNaming(1L, "  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("起个名字");

        assertThat(draft.getStatus()).isEqualTo("PENDING");
        verify(chatAliasService, never()).saveAliases(anyLong(), anyString(), anyString());
        verify(draftMapper, never()).updateById(any(Draft.class));
    }

    @Test
    void confirmNamingDraftRejectsSeparatorOnlyAliases() {
        // "," 不是 blank，但清洗后是空串——那是「拒绝命名」的哨兵值，不能当成成功保存
        Draft draft = namingDraft(1L, 777L, "PRIVATE");
        when(draftMapper.selectById(1L)).thenReturn(draft);

        assertThatThrownBy(() -> confirmNaming(1L, " , , "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("起个名字");

        assertThat(draft.getStatus()).isEqualTo("PENDING");
        verify(chatAliasService, never()).saveAliases(anyLong(), anyString(), anyString());
    }

    @Test
    void confirmNamingDraftUsesPayloadChatIdAndKind() {
        // 草稿行的 chat_id 只对群聊有意义；私聊/异常值都必须以 payload 为准
        Draft draft = namingDraft(1L, 2002L, "GROUP");
        draft.setChatId(9999L);
        when(draftMapper.selectById(1L)).thenReturn(draft);

        confirmNaming(1L, "三班群");

        verify(chatAliasService).saveAliases(2002L, "GROUP", "三班群");
    }

    @Test
    void rejectNamingDraftDoesNotWriteAlias() {
        Draft draft = namingDraft(1L, 777L, "PRIVATE");
        when(draftMapper.selectById(1L)).thenReturn(draft);

        Draft rejected = draftService.rejectDraft(1L);

        assertThat(rejected.getStatus()).isEqualTo("REJECTED");
        verify(chatAliasService, never()).saveAliases(anyLong(), anyString(), anyString());
    }

    @Test
    void confirmReplyStillIgnoresAliasesArgument() {
        Draft draft = new Draft();
        draft.setId(1L);
        draft.setType("REPLY");
        draft.setSenderId(1001L);
        draft.setChatId(2002L);
        Map<String, Object> payload = new HashMap<>();
        payload.put("content", "好的");
        draft.setPayload(payload);
        draft.setStatus("PENDING");
        when(draftMapper.selectById(1L)).thenReturn(draft);

        confirmNaming(1L, "这个参数对回复无意义");

        verify(langBotClient).sendMessage(any());
        verify(chatAliasService, never()).saveAliases(anyLong(), anyString(), anyString());
        assertThat(draft.getStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void replyDraftInPrivateChatExposesFirstAlias() {
        // 「对方」在私聊里就是对方 QQ：他起过名字，草稿卡就该显示第一个名字
        Draft reply = replyDraft(null, 777L, "阿黄");
        when(draftMapper.selectList(any())).thenReturn(List.of(reply));
        when(chatAliasService.displayNames()).thenReturn(Map.of("PRIVATE:777", "黄哥"));

        List<Draft> list = draftService.getDraftList(null, null);

        assertThat(list.get(0).getPayload()).containsEntry("sourceDisplayName", "黄哥");
    }

    @Test
    void replyDraftInGroupExposesGroupAlias() {
        Draft reply = replyDraft(2002L, 1001L, "小张");
        when(draftMapper.selectList(any())).thenReturn(List.of(reply));
        when(chatAliasService.displayNames()).thenReturn(Map.of("GROUP:2002", "三班群"));

        List<Draft> list = draftService.getDraftList(null, null);

        assertThat(list.get(0).getPayload()).containsEntry("sourceDisplayName", "三班群");
    }

    private Draft replyDraft(Long chatId, Long senderId, String senderName) {
        Draft draft = new Draft();
        draft.setId(9L);
        draft.setType("REPLY");
        draft.setStatus("PENDING");
        draft.setChatId(chatId);
        draft.setSenderId(senderId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("content", "在的");
        payload.put("sourceContent", "在吗");
        payload.put("sourceSenderName", senderName);
        draft.setPayload(payload);
        return draft;
    }

    private Draft namingDraft(Long id, Long chatId, String kind) {
        Draft draft = new Draft();
        draft.setId(id);
        draft.setType("NAMING");
        draft.setSenderId(1001L);
        draft.setTitle("起名");
        draft.setStatus("PENDING");
        Map<String, Object> payload = new HashMap<>();
        payload.put("chatId", chatId);
        payload.put("kind", kind);
        payload.put("suggestedName", "阿黄");
        draft.setPayload(payload);
        return draft;
    }

    /** 确认时只带 aliases：模拟前端命名草稿的调用形状 */
    private Draft confirmNaming(Long id, String aliases) {
        DraftConfirmRequest request = new DraftConfirmRequest();
        request.setAliases(aliases);
        return draftService.confirmDraft(id, request);
    }
}
