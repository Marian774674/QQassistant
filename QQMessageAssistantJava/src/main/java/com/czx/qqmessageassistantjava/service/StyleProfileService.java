package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.entity.StyleProfile;

public interface StyleProfileService extends IService<StyleProfile>{

    StyleProfile getBySenderId(Long senderId);

    StyleProfile upsert(Long senderId,String profilePrompt);

    String promptOf(Long senderId);

}
