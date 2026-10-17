package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.dto.FileTaskSyncRequest;
import com.czx.qqmessageassistantjava.entity.FileTask;

import java.util.List;

public interface FileTaskService extends IService<FileTask> {

    FileTask sync(FileTaskSyncRequest request);

    List<FileTask> list(Long senderId, String status);
}