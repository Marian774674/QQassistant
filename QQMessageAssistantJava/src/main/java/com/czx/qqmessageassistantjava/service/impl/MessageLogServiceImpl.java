package com.czx.qqmessageassistantjava.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.MessageLogItem;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.MessageLogService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class MessageLogServiceImpl extends ServiceImpl<MessageLogMapper, MessageLog> implements MessageLogService{

    private static final Set<String> DIRECTIONS = Set.of("IN","OUT");

    @Override
    public int batchSave(List<MessageLogItem> items) {
        if(items.isEmpty() || items == null){
            throw new IllegalArgumentException("日志列表不能为空");
        }
        List<MessageLog> logs = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            MessageLogItem item = items.get(i);
            if (item.getSenderId() == null) {
                throw new BusinessException("第" + (i + 1) + "条 senderId 不能为空");
            }
            if (item.getDirection() == null || !DIRECTIONS.contains(item.getDirection())) {
                throw new BusinessException("第" + (i + 1) + "条 direction 必须为 IN|OUT");
            }
            if (item.getMsgType() == null || item.getMsgType().isBlank()) {
                throw new BusinessException("第" + (i + 1) + "条 msgType 不能为空");
            }
            MessageLog log = new MessageLog();
            BeanUtils.copyProperties(item, log);
            logs.add(log);
        }
        saveBatch(logs);
        return logs.size();
    }
}
