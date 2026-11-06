package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.constant.AppSettingKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 单条判定必须和批量 filter / 试跑 preview 同一条规则链。
 * 尤其：总开关关掉时入口也要全放行，非文本消息永远保留。
 */
@ExtendWith(MockitoExtension.class)
class MessageNoiseFilterTextTest {

    @Mock
    private AppSettingService appSettingService;

    private MessageNoiseFilter filter;

    @BeforeEach
    void setUp() {
        lenient().when(appSettingService.getOrDefault(anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(1));
        filter = new MessageNoiseFilter(appSettingService);
    }

    @Test
    void purePunctuationIsNoise() {
        assertEquals("punctuation", filter.dropReasonForText("……", "text"));
    }

    @Test
    void repeatedCharsAreNoise() {
        assertEquals("repeat", filter.dropReasonForText("hhhh", "text"));
    }

    @Test
    void keepRegexWinsOverNoiseRules() {
        assertNull(filter.dropReasonForText("明天交表", "text"));
    }

    @Test
    void nonTextIsAlwaysKept() {
        assertNull(filter.dropReasonForText("", "file"));
        assertNull(filter.dropReasonForText("……", "image"));
    }

    @Test
    void nullTextIsBlankNoise() {
        assertEquals("blank", filter.dropReasonForText(null, null));
    }

    @Test
    void disabledFilterKeepsEverything() {
        when(appSettingService.getOrDefault(
                eq(AppSettingKeys.NOISE_FILTER_ENABLED), anyString()))
                .thenReturn("false");
        assertNull(filter.dropReasonForText("……", "text"));
        assertNull(filter.dropReasonForText("hhhh", "text"));
    }
}
