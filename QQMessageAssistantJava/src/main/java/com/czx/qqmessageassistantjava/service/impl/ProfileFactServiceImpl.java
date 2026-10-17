package com.czx.qqmessageassistantjava.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.FactManualRequest;
import com.czx.qqmessageassistantjava.dto.FactUpdateRequest;
import com.czx.qqmessageassistantjava.dto.FactUpsertRequest;
import com.czx.qqmessageassistantjava.entity.ProfileFact;
import com.czx.qqmessageassistantjava.mapper.ProfileFactMapper;
import com.czx.qqmessageassistantjava.service.ProfileFactService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProfileFactServiceImpl extends ServiceImpl<ProfileFactMapper, ProfileFact> implements ProfileFactService {
    @Override
    public ProfileFact upsertAuto(FactUpsertRequest request) {
        String factKey = request.getFactKey();
        Long senderId = request.getSenderId();
        String factValue = request.getFactValue();
        require(factKey, factValue, senderId);
        if (request.getConfidence() == null) {
            throw new BusinessException("confidence 不能为空");
        }
        return upsert(senderId, factKey, factValue, request.getConfidence(), "AUTO");
    }

    @Override
    public ProfileFact createManual(FactManualRequest request) {
        String factKey = request.getFactKey();
        Long senderId = request.getSenderId();
        String factValue = request.getFactValue();
        require(factKey, factValue, senderId);
        return upsert(senderId, factKey, factValue, null, "MANUAL");
    }

    @Override
    public ProfileFact updateFact(Long id, FactUpdateRequest request) {
        String factKey = request.getFactKey();
        ProfileFact fact = getById(id);
        if (fact == null) {
            throw new BusinessException("事实不存在");
        }
        if (factKey != null && !factKey.isBlank() && !fact.equals(fact.getFactKey())) {
            ProfileFact clash = findOne(fact.getSenderId(), factKey);
            if (clash != null && !clash.getId().equals(id)) {
                throw new BusinessException("用户已存在该试试键");
            }
            fact.setFactKey(request.getFactKey());
        }
        if (request.getFactValue() != null && !request.getFactValue().isBlank()) {
            fact.setFactValue(request.getFactValue());
        }
        fact.setSource("MANUAL");
        updateById(fact);
        return fact;
    }

    @Override
    public void deleteFact(Long id) {
        if (getById(id) == null) {
            throw new BusinessException("事实不存在");
        }
        removeById(id);
    }

    @Override
    public List<ProfileFact> listBySenderId(Long senderId) {
        return lambdaQuery()
                .eq(senderId != null, ProfileFact::getSenderId, senderId)
                .orderByAsc(ProfileFact::getFactKey)
                .list();
    }

    @Override
    public Map<String, String> factMap(Long senderId) {
        return listBySenderId(senderId).stream()
                .collect(Collectors.toMap(ProfileFact::getFactKey, ProfileFact::getFactValue));
    }

    private ProfileFact upsert(Long senderId, String factKey, String factValue, BigDecimal confidence, String source) {
        ProfileFact existing = findOne(senderId, factKey);
        if (existing != null) {
            if (confidence != null) {
                existing.setConfidence(confidence);
            }
            if ("MANUAL".equals(source)) {
                existing.setSource(source);
            }
            existing.setFactValue(factValue);
            updateById(existing);
            return existing;
        }
        ProfileFact profileFact = new ProfileFact();
        profileFact.setSenderId(senderId);
        profileFact.setFactKey(factKey);
        profileFact.setFactValue(factValue);
        profileFact.setConfidence(confidence);
        profileFact.setSource(source);
        try {
            save(profileFact);
        } catch (DuplicateKeyException e) {
            ProfileFact winner = findOne(senderId, factKey);
            if (winner != null) {
                if (confidence != null) {
                    winner.setConfidence(confidence);
                }
                winner.setFactValue(factValue);
                updateById(winner);
                return winner;
            }
            throw e;
        }
        return profileFact;
    }

    private ProfileFact findOne(Long senderId, String factKey) {
        ProfileFact existing = lambdaQuery()
                .eq(ProfileFact::getSenderId, senderId)
                .eq(ProfileFact::getFactKey, factKey)
                .one();
        return existing;
    }

    private void require(String factKey, String factValue, Long senderId) {
        if (factKey == null) {
            throw new BusinessException("factKey 不能为空");
        }
        if (factValue == null) {
            throw new BusinessException("factValue 不能为空");
        }
        if (senderId == null) {
            throw new BusinessException("senderId 不能为空");
        }
    }


}
