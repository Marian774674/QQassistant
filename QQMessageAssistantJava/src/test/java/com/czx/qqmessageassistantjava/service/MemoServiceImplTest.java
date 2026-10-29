package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.MemoCreateRequest;
import com.czx.qqmessageassistantjava.dto.MemoPatchRequest;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.entity.MemoReminder;
import com.czx.qqmessageassistantjava.mapper.MemoReminderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoServiceImplTest {

    @Autowired private MemoService memoService;
    @Autowired private MemoReminderMapper memoReminderMapper;

    private Memo create(int importance, LocalDateTime due) {
        MemoCreateRequest req = new MemoCreateRequest();
        req.setSenderId(9001L);
        req.setTitle("t");
        req.setImportance(importance);
        req.setDueTime(due);
        return memoService.create(req);
    }

    @Test
    void patchRejectsImportanceOutOfRange() {
        Memo memo = create(3, LocalDateTime.now().plusDays(2));
        MemoPatchRequest patch = new MemoPatchRequest();
        patch.setImportance(6);

        assertThatThrownBy(() -> memoService.patch(memo.getId(), patch))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("importance");
    }

    @Test
    void createRejectsUnknownCategory() {
        MemoCreateRequest req = new MemoCreateRequest();
        req.setSenderId(9002L);
        req.setTitle("t");
        req.setImportance(3);
        req.setCategory("游戏");

        assertThatThrownBy(() -> memoService.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("category");
    }

    @Test
    void pastDueTimeDoesNotCreatePastReminders() {
        Memo memo = create(5, LocalDateTime.now().minusDays(2));
        long past = memoReminderMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MemoReminder>()
                        .eq(MemoReminder::getMemoId, memo.getId())
                        .lt(MemoReminder::getRemindAt, LocalDateTime.now()));
        assertThat(past).isZero();
    }

    @Test
    void importanceExpandsReminderPlanInTimeOrder() {
        Memo memo = create(5, LocalDateTime.now().plusDays(3));

        List<MemoReminder> plan = memoService.reminders(memo.getId());

        assertThat(plan).hasSize(3);
        assertThat(plan).extracting(MemoReminder::getOffsetLabel)
                .containsExactly("24h", "2h", "30min");
        assertThat(plan).allMatch(r -> "PENDING".equals(r.getStatus()));
    }

    @Test
    void lowImportanceHasNoReminders() {
        Memo memo = create(1, LocalDateTime.now().plusDays(3));

        assertThat(memoService.reminders(memo.getId())).isEmpty();
    }

    @Test
    void patchRecalculatesRemindersWhenImportanceChanges() {
        Memo memo = create(3, LocalDateTime.now().plusDays(3));
        assertThat(memoService.reminders(memo.getId())).hasSize(1);

        MemoPatchRequest patch = new MemoPatchRequest();
        patch.setImportance(5);
        memoService.patch(memo.getId(), patch);

        // 旧的一档（2h）被置为 CANCELLED 留档，新计划三档都是 PENDING
        List<MemoReminder> plan = memoService.reminders(memo.getId());
        assertThat(plan).filteredOn(r -> "PENDING".equals(r.getStatus()))
                .extracting(MemoReminder::getOffsetLabel)
                .containsExactly("24h", "2h", "30min");
        assertThat(plan).filteredOn(r -> "CANCELLED".equals(r.getStatus())).hasSize(1);
    }

    @Test
    void patchRejectsUnknownCategory() {
        Memo memo = create(3, LocalDateTime.now().plusDays(2));
        MemoPatchRequest patch = new MemoPatchRequest();
        patch.setCategory("游戏");

        assertThatThrownBy(() -> memoService.patch(memo.getId(), patch))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("category");
    }

    @Test
    void doneMemoCancelsPendingReminders() {
        Memo memo = create(5, LocalDateTime.now().plusDays(3));

        MemoPatchRequest patch = new MemoPatchRequest();
        patch.setStatus("DONE");
        memoService.patch(memo.getId(), patch);

        assertThat(memoService.reminders(memo.getId()))
                .noneMatch(r -> "PENDING".equals(r.getStatus()));
    }

    @Test
    void patchCanClearDueTimeAndCancelsReminders() {
        Memo memo = create(5, LocalDateTime.now().plusDays(3));

        MemoPatchRequest patch = new MemoPatchRequest();
        patch.setClearDueTime(true);
        Memo updated = memoService.patch(memo.getId(), patch);

        assertThat(updated.getDueTime()).isNull();
        assertThat(memoService.reminders(memo.getId()))
                .noneMatch(r -> "PENDING".equals(r.getStatus()));
    }

    @Test
    void dueTimeAcceptsCommonLlmFormats() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        MemoCreateRequest spaced = mapper.readValue(
                "{\"dueTime\":\"2026-10-29 19:00\"}", MemoCreateRequest.class);
        assertThat(spaced.getDueTime()).isEqualTo(LocalDateTime.of(2026, 10, 29, 19, 0));

        MemoCreateRequest zoned = mapper.readValue(
                "{\"dueTime\":\"2026-10-29T19:00:00+08:00\"}", MemoCreateRequest.class);
        assertThat(zoned.getDueTime()).isEqualTo(LocalDateTime.of(2026, 10, 29, 19, 0));

        MemoCreateRequest dateOnly = mapper.readValue(
                "{\"dueTime\":\"2026-10-29\"}", MemoCreateRequest.class);
        assertThat(dateOnly.getDueTime()).isEqualTo(LocalDateTime.of(2026, 10, 29, 0, 0));

        MemoCreateRequest blank = mapper.readValue(
                "{\"dueTime\":\"\"}", MemoCreateRequest.class);
        assertThat(blank.getDueTime()).isNull();
    }
}
