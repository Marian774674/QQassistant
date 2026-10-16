package com.czx.qqmessageassistantjava.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.dto.CollectionTaskRequest;
import com.czx.qqmessageassistantjava.entity.CollectionTask;

public interface CollectionTaskService extends IService<CollectionTask>{

    boolean checkReplied(CollectionTaskRequest request);

    boolean markReplied(CollectionTaskRequest request);

}
