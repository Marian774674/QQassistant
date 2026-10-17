package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.FileTaskSyncRequest;
import com.czx.qqmessageassistantjava.entity.FileTask;
import com.czx.qqmessageassistantjava.mapper.FileTaskMapper;
import com.czx.qqmessageassistantjava.service.FileTaskService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class FileTaskServiceImpl extends ServiceImpl<FileTaskMapper, FileTask> implements FileTaskService {

    private static final Set<String> STATUSES = Set.of(
            "PENDING", "PROCESSING", "NEED_INPUT", "AWAITING_CONFIRM",
            "SENT", "FAILED", "CANCELLED");

    @Override
    public FileTask sync(FileTaskSyncRequest request) {
        if (request.getTaskRef() == null || request.getTaskRef().isBlank()) {
            throw new BusinessException("taskRef 不能为空");
        }
        if (request.getSenderId() == null) {
            throw new BusinessException("senderId 不能为空");
        }
        if (request.getStatus() == null || !STATUSES.contains(request.getStatus())) {
            throw new BusinessException("status 必须是【" + STATUSES + "】其中之一");
        }
        FileTask exiting = lambdaQuery().eq(FileTask::getTaskRef, request.getTaskRef()).one();
        if (exiting != null) {
            fillAttributes(request, exiting);
            updateById(exiting);
            return exiting;
        }
        FileTask task = new FileTask();
        task.setTaskRef(request.getTaskRef());
        fillAttributes(request, task);
        try {
            save(task);
        } catch (DuplicateKeyException e) {
            FileTask winner = lambdaQuery().eq(FileTask::getTaskRef, request.getTaskRef()).one();
            if (winner != null) {
                fillAttributes(request, winner);
                updateById(winner);
                return winner;
            }
            throw e;
        }
        return task;
    }

    @Override
    public List<FileTask> list(Long senderId, String status) {
        if (status != null && !STATUSES.contains(status)) {
            throw new BusinessException("status 必须是【" + STATUSES + "】其中之一");
        }
        return lambdaQuery()
                .eq(senderId != null, FileTask::getSenderId, senderId)
                .eq(status != null, FileTask::getStatus, status)
                .orderByDesc(FileTask::getUpdatedAt)
                .list();
    }

    private static void fillAttributes(FileTaskSyncRequest request, FileTask exiting) {
        exiting.setSenderId(request.getSenderId());
        exiting.setStatus(request.getStatus());
        if (request.getChatId() != null) {
            exiting.setChatId(request.getChatId());
        }
        if (request.getFileName() != null) {
            exiting.setFileName(request.getFileName());
        }
        if (request.getDetail() != null) {
            exiting.setDetail(request.getDetail());
        }
    }
}
