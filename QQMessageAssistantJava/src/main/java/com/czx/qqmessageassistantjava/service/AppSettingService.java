package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.entity.AppSetting;

import java.util.Map;

public interface AppSettingService extends IService<AppSetting> {

    Map<String,String> getAll();

    int putAll(Map<String,String> kv);

    String getOrDefault(String key,String defaultValue);

}
