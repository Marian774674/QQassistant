package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.dto.FactManualRequest;
import com.czx.qqmessageassistantjava.dto.FactUpdateRequest;
import com.czx.qqmessageassistantjava.dto.FactUpsertRequest;
import com.czx.qqmessageassistantjava.entity.ProfileFact;

import java.util.List;
import java.util.Map;

public interface ProfileFactService extends IService<ProfileFact> {

    ProfileFact upsertAuto(FactUpsertRequest request);

    ProfileFact createManual(FactManualRequest request);

    ProfileFact updateFact(Long id, FactUpdateRequest request);

    void deleteFact(Long id);

    List<ProfileFact> listBySenderId(Long senderId);

    Map<String, String> factMap(Long senderId);
}
