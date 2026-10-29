package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.client.LangBotClient;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.FileTaskSyncRequest;
import com.czx.qqmessageassistantjava.dto.SendFileRequest;
import com.czx.qqmessageassistantjava.dto.SendMessageRequest;
import com.czx.qqmessageassistantjava.dto.SendResult;
import com.czx.qqmessageassistantjava.entity.Draft;
import com.czx.qqmessageassistantjava.entity.FileTask;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.service.DraftService;
import com.czx.qqmessageassistantjava.service.FileTaskService;
import com.czx.qqmessageassistantjava.service.MemoService;
import com.czx.qqmessageassistantjava.service.ProfileFactService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DraftDispatchTest {

    @Autowired
    private DraftService draftService;

    @Autowired
    private MemoService memoService;

    @Autowired
    private ProfileFactService profileFactService;

    @Autowired
    private FileTaskService fileTaskService;

    @MockitoBean
    private LangBotClient langBotClient;

    private Draft newDraft(String type, Map<String, Object> payload) {
        Draft draft = new Draft();
        draft.setType(type);
        draft.setSenderId(1001L);
        draft.setChatId(2002L);
        draft.setTitle("t");
        draft.setPayload(payload);
        draft.setStatus("PENDING");
        draftService.save(draft);
        return draft;
    }

    @Test
    void replyDispatchSendsText() {
        when(langBotClient.sendMessage(any())).thenReturn(new SendResult());

        Map<String, Object> payload = new HashMap<>();
        payload.put("content", "好的，我看看");
        payload.put("targetType", "private");
        payload.put("targetId", 2002);
        Draft draft = newDraft("REPLY", payload);

        draftService.confirmDraft(draft.getId());

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(langBotClient).sendMessage(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("好的，我看看");
        assertThat(captor.getValue().getTarget().getType()).isEqualTo("private");
        assertThat(captor.getValue().getTarget().getId()).isEqualTo(2002L);
        assertThat(draftService.getById(draft.getId()).getStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void replyDispatchDefaultsToGroupWhenChatIdPresent() {
        when(langBotClient.sendMessage(any())).thenReturn(new SendResult());

        Map<String, Object> payload = new HashMap<>();
        payload.put("content", "默认目标");
        Draft draft = newDraft("REPLY", payload);   // 无 targetType / targetId

        draftService.confirmDraft(draft.getId());

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(langBotClient).sendMessage(captor.capture());
        // 草稿 chatId=2002 是群号，必须按 group 发回群里，不能当私聊发给 2002
        assertThat(captor.getValue().getTarget().getId()).isEqualTo(2002L);
        assertThat(captor.getValue().getTarget().getType()).isEqualTo("group");
    }

    @Test
    void fileDispatchSendsFile() {
        when(langBotClient.sendFile(any())).thenReturn(new SendResult());

        Map<String, Object> payload = new HashMap<>();
        payload.put("taskRef", "ft_0001");
        payload.put("fileName", "表单.xlsx");
        payload.put("fileRef", "local://out/ft_0001.xlsx");
        Draft draft = newDraft("FILE", payload);

        draftService.confirmDraft(draft.getId());

        ArgumentCaptor<SendFileRequest> captor = ArgumentCaptor.forClass(SendFileRequest.class);
        verify(langBotClient).sendFile(captor.capture());
        assertThat(captor.getValue().getFileRef()).isEqualTo("local://out/ft_0001.xlsx");
        assertThat(captor.getValue().getFileName()).isEqualTo("表单.xlsx");
        verify(langBotClient, never()).sendMessage(any());
    }

    @Test
    void fileConfirmMarksFileTaskSent() {
        when(langBotClient.sendFile(any())).thenReturn(new SendResult());

        FileTaskSyncRequest sync = new FileTaskSyncRequest();
        sync.setTaskRef("ft_sent");
        sync.setSenderId(1001L);
        sync.setStatus("AWAITING_CONFIRM");
        fileTaskService.sync(sync);

        Map<String, Object> payload = new HashMap<>();
        payload.put("taskRef", "ft_sent");
        payload.put("fileName", "表单_filled.xlsx");
        payload.put("fileRef", "local:///tmp/表单_filled.xlsx");
        Draft draft = newDraft("FILE", payload);

        draftService.confirmDraft(draft.getId());

        assertThat(fileTaskService.lambdaQuery()
                .eq(FileTask::getTaskRef, "ft_sent").one().getStatus()).isEqualTo("SENT");
    }

    @Test
    void fileConfirmKeepsTaskWhenSendFails() {
        when(langBotClient.sendFile(any())).thenThrow(new BusinessException("桥挂了"));

        FileTaskSyncRequest sync = new FileTaskSyncRequest();
        sync.setTaskRef("ft_fail");
        sync.setSenderId(1001L);
        sync.setStatus("AWAITING_CONFIRM");
        fileTaskService.sync(sync);

        Map<String, Object> payload = new HashMap<>();
        payload.put("taskRef", "ft_fail");
        payload.put("fileName", "表单.xlsx");
        payload.put("fileRef", "local:///tmp/表单.xlsx");
        Draft draft = newDraft("FILE", payload);

        assertThatThrownBy(() -> draftService.confirmDraft(draft.getId()))
                .isInstanceOf(BusinessException.class);
        assertThat(fileTaskService.lambdaQuery()
                .eq(FileTask::getTaskRef, "ft_fail").one().getStatus())
                .isEqualTo("AWAITING_CONFIRM");
    }

    @Test
    void fileDraftRequiresTaskRefAndFileRef() {
        Map<String, Object> noRef = new HashMap<>();
        noRef.put("taskRef", "ft_x");
        assertThatThrownBy(() -> draftService.createDraft(fileDraft(noRef)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("fileRef");

        Map<String, Object> noTask = new HashMap<>();
        noTask.put("fileRef", "local:///tmp/x.xlsx");
        assertThatThrownBy(() -> draftService.createDraft(fileDraft(noTask)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("taskRef");
    }

    private com.czx.qqmessageassistantjava.dto.DraftCreateRequest fileDraft(Map<String, Object> payload) {
        var req = new com.czx.qqmessageassistantjava.dto.DraftCreateRequest();
        req.setType("FILE");
        req.setSenderId(1001L);
        req.setTitle("t");
        req.setPayload(payload);
        return req;
    }

    @Test
    void factDispatchWritesProfileFact() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("factKey", "专业");
        payload.put("factValue", "计算机科学与技术");
        payload.put("confidence", 0.72);
        Draft draft = newDraft("FACT", payload);

        draftService.confirmDraft(draft.getId());

        assertThat(profileFactService.factsMap(1001L)).containsEntry("专业", "计算机科学与技术");
        verifyNoInteractions(langBotClient);
    }

    @Test
    void memoDispatchStillWorks() {
        Map<String, Object> payload = new HashMap();
        payload.put("title", "班会");
        payload.put("importance", 3);
        Draft draft = newDraft("MEMO", payload);

        Draft confirmed = draftService.confirmDraft(draft.getId());

        assertThat(confirmed.getStatus()).isEqualTo("CONFIRMED");
        assertThat(memoService.lambdaQuery().eq(Memo::getTitle, "班会").exists()).isTrue();
        verifyNoInteractions(langBotClient);
    }
}
