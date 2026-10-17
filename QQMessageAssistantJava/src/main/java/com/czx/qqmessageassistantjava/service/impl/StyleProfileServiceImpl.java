package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.entity.StyleProfile;
import com.czx.qqmessageassistantjava.mapper.StyleProfileMapper;
import com.czx.qqmessageassistantjava.service.StyleProfileService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class StyleProfileServiceImpl extends ServiceImpl<StyleProfileMapper, StyleProfile> implements StyleProfileService {
    @Override
    public StyleProfile getBySenderId(Long senderId) {
        return lambdaQuery().eq(StyleProfile::getSenderId, senderId).one();
    }

    @Override
    public StyleProfile upsert(Long senderId, String profilePrompt) {
        if (senderId == null) {
            throw new BusinessException("senderId 不能为空");
        }
        if (profilePrompt == null || profilePrompt.isBlank()) {
            throw new BusinessException("profilePrompt 不能为空");
        }
        StyleProfile exiting = getBySenderId(senderId);
        if (exiting == null) {
            exiting = new StyleProfile();
            exiting.setSenderId(senderId);
            exiting.setProfilePrompt(profilePrompt);
            exiting.setLastSummarizedAt(LocalDateTime.now());
            save(exiting);
        }
        exiting.setProfilePrompt(profilePrompt);
        exiting.setLastSummarizedAt(LocalDateTime.now());
        updateById(exiting);
        return exiting;
    }

    @Override
    public String promptOf(Long senderId) {
        StyleProfile profile = getBySenderId(senderId);
        return profile == null ? "" : profile.getProfilePrompt();
    }
}
