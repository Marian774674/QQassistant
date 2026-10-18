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
    void pastDueTimeDoesNotCreatePastReminders() {
        Memo memo = create(5, LocalDateTime.now().minusDays(2));
        long past = memoReminderMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MemoReminder>()
                        .eq(MemoReminder::getMemoId, memo.getId())
                        .lt(MemoReminder::getRemindAt, LocalDateTime.now()));
        assertThat(past).isZero();
    }
}
