package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.dto.MessageLogItem;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import org.springframework.stereotype.Service;

import java.util.List;

public interface MessageLogService extends IService<MessageLog> {

    int batchSave(List<MessageLogItem> items);

}
