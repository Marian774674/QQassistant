package com.czx.qqmessageassistantjava.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.controller.MemoController;
import com.czx.qqmessageassistantjava.dto.MemoCreateRequest;
import com.czx.qqmessageassistantjava.dto.MemoPatchRequest;
import com.czx.qqmessageassistantjava.entity.Draft;
import com.czx.qqmessageassistantjava.entity.InterceptRule;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.entity.MemoReminder;
import com.czx.qqmessageassistantjava.mapper.MemoMapper;
import com.czx.qqmessageassistantjava.mapper.MemoReminderMapper;
import com.czx.qqmessageassistantjava.service.MemoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MemoServiceImpl extends ServiceImpl<MemoMapper, Memo> implements MemoService {

    private static final Set<String> CATEGORIES =
            Set.of("学业","社交","健康","财务","日常","其他");
    private static final Set<String> PATCH_STATUSES = Set.of("DONE","CANCELLED");
    private static final Map<Integer,List<String>> REMINDER_STRATEGY = Map.of(
           5, List.of("24h","2h","30min"),
           4, List.of("24h","1h"),
           3, List.of("2h"),
           2, List.of("当天"),
           1, List.of()
    );

    @Autowired
    private MemoReminderMapper memoReminderMapper;

    @Override
    @Transactional
    public Memo create(MemoCreateRequest request) {
        if(request.getSenderId() == null){
            throw new BusinessException("SenderId 不能为空");
        }
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new BusinessException("title 不能为空");
        }
        if (request.getImportance() == null || request.getImportance() < 1 || request.getImportance() > 5) {
            throw new BusinessException("importance 必须为 1-5");
        }
        Memo memo = BeanUtil.copyProperties(request, Memo.class);
        memo.setStatus("ACTIVE");
        save(memo);
        expandReminders(memo);
        return memo;
    }

    @Override
    public List<Memo> list(String status, Long senderId) {
        return lambdaQuery()
                .eq(status != null, Memo::getStatus, status)
                .eq(senderId != null, Memo::getSenderId, senderId)
                .orderByDesc(Memo::getCreatedAt)
                .list();
    }

    @Override
    @Transactional
    public Memo patch(Long id, MemoPatchRequest request) {
        Memo memo = getById(id);
        if (memo == null){
            throw new BusinessException("备忘录不存在");
        }
        if(request.getStatus()!=null && !PATCH_STATUSES.contains(request.getStatus())){
            throw new BusinessException("status 只能为 " + PATCH_STATUSES + " 之一");
        }
        boolean needRecalc = (request.getImportance() != null && !memo.getImportance().equals(request.getImportance())
            || request.getDueTime() != null && !request.getDueTime().equals(memo.getDueTime()));
        BeanUtil.copyProperties(request,memo, CopyOptions.create().ignoreNullValue());
        updateById(memo);

        if("DONE".equals(memo.getStatus()) || "CANCELLED".equals(memo.getStatus())){
            cancelPendingReminders(id);
        } else if (needRecalc) {
            cancelPendingReminders(id);
            expandReminders(memo);
        }
        return memo;
    }

    @Override
    @Transactional
    public Memo createFromDraft(Draft draft) {
        Map<String, Object> payload = draft.getPayload();
        MemoCreateRequest request = new MemoCreateRequest();
        request.setSenderId(draft.getSenderId());
        request.setChatId(draft.getChatId());
        request.setTitle((String) payload.get("title"));
        request.setContent((String) payload.get("content"));
        request.setCategory((String) payload.get("category"));
        Object importance = payload.get("importance");
        if (importance instanceof Number num) {
            request.setImportance(num.intValue());
        }
        Object dueTime = payload.get("dueTime");
        if (dueTime instanceof String str && !str.isBlank()) {
            request.setDueTime(LocalDateTime.parse(str));
        }
        return create(request);
    }

    private void expandReminders(Memo memo) {
        if (memo.getDueTime() == null)return;
        for (String label : REMINDER_STRATEGY.get(memo.getImportance())) {
            MemoReminder reminder = new MemoReminder();
            reminder.setMemoId(memo.getId());
            reminder.setOffsetLabel(label);
            reminder.setRemindAt(computeRemindAt(memo.getDueTime(), label));
            reminder.setStatus("PENDING");
            memoReminderMapper.insert(reminder);
        }
    }

    private LocalDateTime computeRemindAt(LocalDateTime dueTime, String label) {
        return switch(label){
            case "24h" -> dueTime.minusHours(24);
            case "2h" -> dueTime.minusHours(2);
            case "1h" -> dueTime.minusHours(1);
            case "30min" -> dueTime.minusMinutes(30);
            case "当天" -> dueTime.toLocalDate().atTime(9, 0);
            default -> throw new BusinessException("非法的提醒时间 " + label);
        };
    }

    private void cancelPendingReminders(Long id) {
        LambdaUpdateWrapper<MemoReminder> wrapper = new LambdaUpdateWrapper<MemoReminder>()
                .eq(MemoReminder::getMemoId, id)
                .eq(MemoReminder::getStatus, "PENDING")
                .set(MemoReminder::getStatus, "CANCELLED");
        memoReminderMapper.update(null, wrapper);
    }
}
