package com.czx.qqmessageassistantjava.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.InterceptRuleRequest;
import com.czx.qqmessageassistantjava.entity.InterceptRule;
import com.czx.qqmessageassistantjava.mapper.InterceptRuleMapper;
import com.czx.qqmessageassistantjava.service.InterceptRuleService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InterceptRuleServiceImpl extends ServiceImpl<InterceptRuleMapper, InterceptRule> implements InterceptRuleService {

    @Override
    public List<InterceptRule> listAllRule() {
        return lambdaQuery().orderByAsc(InterceptRule::getPriority).list();
    }

    @Override
    public InterceptRule createRule(InterceptRuleRequest request) {
        if(request.getName() == null || request.getName().isBlank()){
            throw new IllegalArgumentException("规则名称不能为空");
        }
        InterceptRule interceptRule = new InterceptRule();
        BeanUtil.copyProperties(request, interceptRule,CopyOptions.create().ignoreNullValue());
        save(interceptRule);
        return interceptRule;
    }

    @Override
    public InterceptRule updateRule(Long id, InterceptRuleRequest request) {
        InterceptRule rule = getById(id);
        if (rule == null){
            throw new BusinessException("规则不存在");
        }
        BeanUtil.copyProperties(request, rule,CopyOptions.create().ignoreNullValue());
        updateById(rule);
        return rule;
    }

    @Override
    public void deleteRule(Long id) {
        if (getById(id) == null)
            throw new BusinessException("规则不存在");
        removeById(id);
    }

    @Override
    public List<InterceptRule> listEnabledRule() {
        return lambdaQuery()
                .eq(InterceptRule::getEnabled, true)
                .orderByAsc(InterceptRule::getPriority)
                .list();
    }
}
