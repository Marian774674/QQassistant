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

    @Override
    public void updateStatus(String taskRef, String status) {
        if (taskRef == null || taskRef.isBlank() || status == null) {
            return;
        }
        lambdaUpdate()
                .eq(FileTask::getTaskRef, taskRef)
                .set(FileTask::getStatus, status)
                .update();
    }

    private static void fillAttributes(FileTaskSyncRequest request, FileTask exiting) {
        exiting.setSenderId(request.getSenderId());
        exiting.setStatus(request.getStatus());
        if (request.getChatId() != null) {
            exiting.setChatId(request.getChatId());
        }
        if (request.getConversationId() != null) {
            exiting.setConversationId(request.getConversationId());
        }
        if (request.getFileName() != null) {
            exiting.setFileName(request.getFileName());
        }
        if (request.getRequest() != null) {
            exiting.setRequest(request.getRequest());
        }
        if (request.getPendingRequest() != null) {
            exiting.setPendingRequest(request.getPendingRequest());
        }
        if (request.getMissing() != null) {
            exiting.setMissing(request.getMissing());
        }
        if (request.getConfidence() != null) {
            exiting.setConfidence(request.getConfidence());
        }
        if (request.getNeedsConfirm() != null) {
            exiting.setNeedsConfirm(request.getNeedsConfirm());
        }
        if (request.getFileRef() != null) {
            exiting.setFileRef(request.getFileRef());
        }
        if (request.getOutputRef() != null) {
            exiting.setOutputRef(request.getOutputRef());
        }
        if (request.getSourceMessageId() != null) {
            exiting.setSourceMessageId(request.getSourceMessageId());
        }
        if (request.getIdempotencyKey() != null) {
            exiting.setIdempotencyKey(request.getIdempotencyKey());
        }
        if (request.getDetail() != null) {
            exiting.setDetail(request.getDetail());
        }
    }
}
