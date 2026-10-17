package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.entity.AppSetting;
import com.czx.qqmessageassistantjava.mapper.AppSettingMapper;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AppSettingServiceImpl extends ServiceImpl<AppSettingMapper, AppSetting> implements AppSettingService {
    @Override
    public Map<String, String> getAll() {
        Map<String, String> result = new LinkedHashMap<>();
        for (AppSetting setting : lambdaQuery().orderByAsc(AppSetting::getSettingKey).list()) {
            result.put(setting.getSettingKey(), setting.getSettingValue());
        }
        return result;
    }

    @Override
    @Transactional
    public int putAll(Map<String, String> kv) {
        if (kv == null || kv.isEmpty()) {
            throw new BusinessException("配置不能为空");
        }
        int count = 0;
        for (Map.Entry<String, String> entry : kv.entrySet()) {
            String key=entry.getKey();
            String value =entry.getValue();
            if (key == null||key.isBlank()){
                throw new BusinessException("配置键不能为空");
            }
            AppSetting exiting = lambdaQuery().eq(AppSetting::getSettingKey, key).one();
            if(exiting!=null){
                exiting.setSettingValue(value);
                updateById(exiting);
                ++count;
                continue;
            }
            AppSetting setting = new AppSetting();
            setting.setSettingKey(key);
            setting.setSettingValue(value);
            save(setting);
            ++count;
        }
        return count;
    }

    @Override
    public String getOrDefault(String key, String defaultValue) {
        AppSetting setting = lambdaQuery().eq(AppSetting::getSettingKey, key).one();
        return setting == null || setting.getSettingValue() == null
                ? defaultValue : setting.getSettingValue();
    }
}
