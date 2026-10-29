package com.czx.qqmessageassistantjava.schedule;

import com.czx.qqmessageassistantjava.client.LangBotClient;
import com.czx.qqmessageassistantjava.dto.SendMessageRequest;
import com.czx.qqmessageassistantjava.dto.SendResult;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.entity.MemoReminder;
import com.czx.qqmessageassistantjava.mapper.MemoReminderMapper;
import com.czx.qqmessageassistantjava.service.MemoService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoReminderSchedulerTest {

    @Autowired
    private MemoReminderScheduler scheduler;

    @Autowired
    private MemoService memoService;

    @Autowired
    private MemoReminderMapper memoReminderMapper;

    @MockitoBean
    private LangBotClient langBotClient;

    private Memo activeMemo(int importance) {
        Memo memo = new Memo();
        memo.setSenderId(1401L);
        memo.setTitle("班会");
        memo.setContent("周四晚 7 点");
        memo.setImportance(importance);
        memo.setStatus("ACTIVE");
        memo.setDueTime(LocalDateTime.now().plusHours(5));
        memoService.save(memo);
        return memo;
    }

    private MemoReminder dueReminder(Long memoId) {
        MemoReminder reminder = new MemoReminder();
        reminder.setMemoId(memoId);
        reminder.setOffsetLabel("2h");
        reminder.setRemindAt(LocalDateTime.now().minusMinutes(1));
        reminder.setStatus("PENDING");
        memoReminderMapper.insert(reminder);
        return reminder;
    }

    @Test
    void dueReminderIsPushedAndMarkedSent() {
        when(langBotClient.sendMessage(any())).thenReturn(new SendResult());
        Memo memo = activeMemo(3);
        MemoReminder reminder = dueReminder(memo.getId());

        int sent = scheduler.scanOnce();

        assertThat(sent).isEqualTo(1);
        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(langBotClient).sendMessage(captor.capture());
        assertThat(captor.getValue().getContent()).contains("班会");
        assertThat(captor.getValue().getBizRef()).startsWith("memo:" + memo.getId() + ":");
        assertThat(captor.getValue().getTarget().getId()).isEqualTo(1401L);

        assertThat(memoReminderMapper.selectById(reminder.getId()).getStatus()).isEqualTo("SENT");
        assertThat(memoReminderMapper.selectById(reminder.getId()).getSentAt()).isNotNull();
        // 没有剩余待发提醒 → AWAITING_REPLY
        assertThat(memoService.getById(memo.getId()).getStatus()).isEqualTo("AWAITING_REPLY");
    }

    @Test
    void eachReminderGetsOwnBizRefAndSkipsHumanDelay() {
        when(langBotClient.sendMessage(any())).thenReturn(new SendResult());
        Memo memo = activeMemo(5);
        dueReminder(memo.getId());
        dueReminder(memo.getId());

        int sent = scheduler.scanOnce();

        assertThat(sent).isEqualTo(2);
        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(langBotClient, times(2)).sendMessage(captor.capture());
        // 同一备忘的多档提醒共用 bizRef 会被发送桥 24h 幂等吞掉，只发第一条
        assertThat(captor.getAllValues()).extracting(SendMessageRequest::getBizRef)
                .doesNotHaveDuplicates()
                .allMatch(ref -> ref.startsWith("memo:" + memo.getId() + ":"));
        // 提醒有明确时间点，不能为了拟人延迟把整批推送拖成几分钟
        assertThat(captor.getAllValues()).extracting(SendMessageRequest::getDelayMs)
                .containsOnly(0);
    }

    @Test
    void futureReminderNotPickedUp() {
        Memo memo = activeMemo(3);
        MemoReminder reminder = new MemoReminder();
        reminder.setMemoId(memo.getId());
        reminder.setOffsetLabel("2h");
        reminder.setRemindAt(LocalDateTime.now().plusHours(1));
        reminder.setStatus("PENDING");
        memoReminderMapper.insert(reminder);

        assertThat(scheduler.scanOnce()).isZero();
        verifyNoInteractions(langBotClient);
    }

    @Test
    void memoDoneCancelsReminderInsteadOfSending() {
        Memo memo = activeMemo(3);
        memo.setStatus("DONE");
        memoService.updateById(memo);
        MemoReminder reminder = dueReminder(memo.getId());

        assertThat(scheduler.scanOnce()).isZero();
        verifyNoInteractions(langBotClient);
        assertThat(memoReminderMapper.selectById(reminder.getId()).getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void sendFailureKeepsReminderPending() {
        when(langBotClient.sendMessage(any()))
                .thenThrow(new com.czx.qqmessageassistantjava.common.BusinessException("LangBot 挂了"));
        Memo memo = activeMemo(3);
        MemoReminder reminder = dueReminder(memo.getId());

        assertThat(scheduler.scanOnce()).isZero();
        assertThat(memoReminderMapper.selectById(reminder.getId()).getStatus()).isEqualTo("PENDING");
    }

    @Test
    void overdueSweepMarksExpiredMemos() {
        Memo memo = activeMemo(3);
        memo.setStatus("ACTIVE");
        memo.setDueTime(LocalDateTime.now().minusHours(1));
        memoService.updateById(memo);

        scheduler.scanOnce();

        assertThat(memoService.getById(memo.getId()).getStatus()).isEqualTo("OVERDUE");
    }
}
