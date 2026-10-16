package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.dto.InterceptRuleRequest;
import com.czx.qqmessageassistantjava.entity.InterceptRule;

import java.util.List;

public interface InterceptRuleService extends IService<InterceptRule> {

    List<InterceptRule> listAllRule();

    InterceptRule createRule(InterceptRuleRequest request);

    InterceptRule updateRule(Long id,InterceptRuleRequest request);

    void deleteRule(Long id);

    List<InterceptRule> listEnabledRule();
}
