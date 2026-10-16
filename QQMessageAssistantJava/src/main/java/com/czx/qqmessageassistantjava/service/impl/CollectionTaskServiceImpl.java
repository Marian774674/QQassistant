package com.czx.qqmessageassistantjava.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.CollectionTaskRequest;
import com.czx.qqmessageassistantjava.entity.CollectionTask;
import com.czx.qqmessageassistantjava.mapper.CollectionTaskMapper;
import com.czx.qqmessageassistantjava.service.CollectionTaskService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class CollectionTaskServiceImpl extends ServiceImpl<CollectionTaskMapper, CollectionTask> implements CollectionTaskService {
    @Override
    public boolean checkReplied(CollectionTaskRequest request) {
        validate(request);
        return lambdaQuery()
                .eq(CollectionTask::getChatId, request.getChatId())
                .eq(CollectionTask::getTaskKey, request.getTaskKey())
                .eq(CollectionTask::getStatus, "REPLIED")
                .exists();
    }


    @Override
    public boolean markReplied(CollectionTaskRequest request) {
        validate(request);
        CollectionTask query = getByRequest(request);
        if (query != null){
            toReplied(query);
            return true;
        }
        CollectionTask task = BeanUtil.copyProperties(request, CollectionTask.class);
        task.setStatus("REPLIED");
        task.setRepliedAt(LocalDateTime.now());
        try {
            save(task);
        } catch (DuplicateKeyException e) {
            CollectionTask winner = getByRequest(request);
            if (winner != null) {
                toReplied(winner);
            }
        }
        return true;
    }

    private CollectionTask getByRequest(CollectionTaskRequest request) {
        return lambdaQuery()
                .eq(CollectionTask::getChatId, request.getChatId())
                .eq(CollectionTask::getTaskKey, request.getTaskKey())
                .one();
    }

    private void validate(CollectionTaskRequest request) {
        if (request.getChatId() == null) {
            throw new BusinessException("chatId 不能为空");
        }
        if (request.getTaskType() == null || request.getTaskType().isBlank()) {
            throw new BusinessException("taskType 不能为空");
        }
        if (request.getTaskKey() == null || request.getTaskKey().isBlank()) {
            throw new BusinessException("taskKey 不能为空");
        }
    }
    private void toReplied(CollectionTask task) {
        task.setStatus("REPLIED");
        task.setRepliedAt(LocalDateTime.now());
        updateById(task);
    }
}
