package com.czx.qqmessageassistantjava.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czx.qqmessageassistantjava.client.LangBotClient;
import com.czx.qqmessageassistantjava.dto.SendMessageRequest;
import com.czx.qqmessageassistantjava.dto.SendTarget;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.entity.MemoReminder;
import com.czx.qqmessageassistantjava.mapper.MemoReminderMapper;
import com.czx.qqmessageassistantjava.service.MemoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Component
public class MemoReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemoReminderScheduler.class);

    private static final Set<String> PUSHABLE = Set.of("ACTIVE", "REMINDED", "AWAITING_REPLY");

    @Autowired
    private MemoReminderMapper memoReminderMapper;
    @Autowired
    private LangBotClient langBotClient;
    @Autowired
    private MemoService memoService;

    @Value("${app.reminder.batch-size:50}")
    private int batchSize = 50;

    @Value("${app.reminder.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${app.reminder.scan-interval-ms:60000}")
    public void scan() {
        if (!enabled) return;
        try {
            int sent = scanOnce();
            if (sent > 0) {
                log.info("提醒扫描完成，推送 {} 条", sent);
            }
        } catch (Exception e) {
            log.error("提醒扫描发生错误", e);
        }
    }

    public int scanOnce() {
        List<MemoReminder> due = memoReminderMapper.selectList(
                new LambdaQueryWrapper<MemoReminder>()
                        .eq(MemoReminder::getStatus, "PENDING")
                        .le(MemoReminder::getRemindAt, LocalDateTime.now())
                        .orderByAsc(MemoReminder::getRemindAt)
                        .last("limit " + batchSize)
        );
        int sent = 0;
        for (MemoReminder reminder : due) {
            Memo memo = memoService.getById(reminder.getMemoId());
            if (memo == null || !PUSHABLE.contains(memo.getStatus())) {
                cancel(reminder);
                continue;
            }
            try {
                langBotClient.sendMessage(buildRequest(memo, reminder));
            } catch (Exception e) {
                log.warn("提醒推送失败 reminderId={} memoId={}", reminder.getId(), memo.getId(), e);
                continue;
            }
            reminder.setStatus("SENT");
            reminder.setSentAt(LocalDateTime.now());
            memoReminderMapper.updateById(reminder);
            refreshMemoStatus(memo);
            ++sent;
        }
        overdueSweep();
        return sent;
    }

    private SendMessageRequest buildRequest(Memo memo, MemoReminder reminder) {
        SendMessageRequest request = new SendMessageRequest();
        request.setTarget(new SendTarget("private", memo.getSenderId()));
        request.setContent(composeText(memo));
        // 一档提醒一个 bizRef：同一备忘 24h/2h/30min 多档推送若共用键，
        // 发送桥会按 bizRef 幂等，只发第一条、其余被静默吞掉。
        request.setBizRef("memo:" + memo.getId() + ":" + reminder.getId());
        // 提醒是到点推送，不是闲聊天：不做拟人延迟，否则整批提醒会串行拖成几分钟
        request.setDelayMs(0);
        return request;
    }

    private String composeText(Memo memo) {
        StringBuilder sb = new StringBuilder();
        sb.append("【提醒·").append(memo.getImportance()).append("级】").append(memo.getTitle());
        if (memo.getContent() != null && !memo.getContent().isBlank()) {
            sb.append('\n').append(memo.getContent());
        }
        if (memo.getDueTime() != null) {
            sb.append("\n时间：").append(memo.getDueTime());
        }
        sb.append("\n回「完成」或「取消」即可收尾");
        return sb.toString();
    }

    private void refreshMemoStatus(Memo memo) {
        boolean morePending = memoReminderMapper.exists(
                new LambdaQueryWrapper<MemoReminder>()
                        .eq(MemoReminder::getMemoId, memo.getId())
                        .eq(MemoReminder::getStatus, "PENDING"));
        String next = morePending ? "REMINDED" : "AWAITING_REPLY";
        if (!next.equals(memo.getStatus())) {
            memo.setStatus(next);
            memoService.updateById(memo);
        }
    }

    private void cancel(MemoReminder reminder) {
        reminder.setStatus("CANCELLED");
        memoReminderMapper.updateById(reminder);
    }

    /**
     * 已过 due_time 但仍未收尾的备忘 → OVERDUE
     */
    private void overdueSweep() {
        List<Memo> overdue = memoService.lambdaQuery()
                .in(Memo::getStatus, PUSHABLE)
                .isNotNull(Memo::getDueTime)
                .lt(Memo::getDueTime, LocalDateTime.now())
                .list();
        for (Memo memo : overdue) {
            memo.setStatus("OVERDUE");
            memoService.updateById(memo);
        }
    }
}

