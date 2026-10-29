package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.FactManualRequest;
import com.czx.qqmessageassistantjava.dto.FactUpsertRequest;
import com.czx.qqmessageassistantjava.entity.ProfileFact;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProfileFactServiceTest {

    @Autowired
    private ProfileFactService profileFactService;

    @Test
    void upsertInsertsThenOverwrites() {
        FactUpsertRequest first = new FactUpsertRequest();
        first.setSenderId(1001L);
        first.setFactKey("学号");
        first.setFactValue("2023001");
        first.setConfidence(new BigDecimal("0.95"));
        ProfileFact created = profileFactService.upsertAuto(first);
        assertThat(created.getId()).isNotNull();
        assertThat(created.getSource()).isEqualTo("AUTO");

        FactUpsertRequest second = new FactUpsertRequest();
        second.setSenderId(1001L);
        second.setFactKey("学号");
        second.setFactValue("2023099");
        second.setConfidence(new BigDecimal("0.95"));
        ProfileFact updated = profileFactService.upsertAuto(second);
        assertThat(updated.getId()).isEqualTo(created.getId());
        assertThat(updated.getFactValue()).isEqualTo("2023099");
    }

    @Test
    void factsMapReturnsKeyValuePairs() {
        FactManualRequest req = new FactManualRequest();
        req.setSenderId(1002L);
        req.setFactKey("姓名");
        req.setFactValue("张三");
        profileFactService.createManual(req);

        assertThat(profileFactService.factsMap(1002L)).containsEntry("姓名", "张三");
    }

    @Test
    void confidenceOutOfRangeRejected() {
        FactUpsertRequest req = new FactUpsertRequest();
        req.setSenderId(1004L);
        req.setFactKey("专业");
        req.setFactValue("计算机");
        req.setConfidence(new BigDecimal("1.5"));

        assertThatThrownBy(() -> profileFactService.upsertAuto(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    void blankKeyRejected() {
        FactUpsertRequest req = new FactUpsertRequest();
        req.setSenderId(1003L);
        req.setFactKey("  ");
        req.setFactValue("x");
        assertThatThrownBy(() -> profileFactService.upsertAuto(req))
                .isInstanceOf(BusinessException.class);
    }
}