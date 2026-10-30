package com.czx.qqmessageassistantjava.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.MessageLogItem;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.MessageLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class MessageLogServiceImpl extends ServiceImpl<MessageLogMapper, MessageLog> implements MessageLogService{

    private static final Logger log = LoggerFactory.getLogger(MessageLogServiceImpl.class);
    private static final Set<String> DIRECTIONS = Set.of("IN","OUT");

    @Override
    public int batchSave(List<MessageLogItem> items) {
        if(items == null || items.isEmpty()){
            throw new BusinessException("日志列表不能为空");
        }
        int saved = 0;
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
            MessageLog messageLog = new MessageLog();
            BeanUtils.copyProperties(item, messageLog);
            // 别用库里的 CURRENT_TIMESTAMP：那台 MySQL 跑在 UTC，而整理的时间窗来自 JVM 本地钟，
            // 差 8 小时后每小时都选不中消息。这里给死本地时间，两边同一个轴。
            if (messageLog.getCreatedAt() == null) {
                messageLog.setCreatedAt(LocalDateTime.now());
            }
            try {
                // 逐条写：dedupeKey 撞唯一键时跳过该条，不影响同批其他日志
                save(messageLog);
                saved++;
            } catch (DuplicateKeyException e) {
                log.debug("duplicate message log ignored: {}", item.getDedupeKey());
            }
        }
        return saved;
    }

    @Override
    public List<MessageLog> list(Long senderId, String conversationId, String direction, Integer limit) {
        if (direction != null && !DIRECTIONS.contains(direction)) {
            throw new BusinessException("direction 必须为 IN|OUT");
        }
        int size = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        return lambdaQuery()
                .eq(senderId != null, MessageLog::getSenderId, senderId)
                .eq(conversationId != null, MessageLog::getConversationId, conversationId)
                .eq(direction != null, MessageLog::getDirection, direction)
                .orderByDesc(MessageLog::getId)
                .last("LIMIT " + size)
                .list();
    }
}
