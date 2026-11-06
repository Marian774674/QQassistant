package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.constant.TuningDefaults;
import com.czx.qqmessageassistantjava.dto.NoiseFilterPreviewRequest;
import com.czx.qqmessageassistantjava.dto.NoiseFilterUpdateRequest;
import com.czx.qqmessageassistantjava.vo.NoiseFilterVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设置页开放噪音过滤的守门测试：坏正则要挡在保存和试跑两处，
 * 「试一试」必须按编辑框草稿判定，且绝不写库。
 */
@ExtendWith(MockitoExtension.class)
class NoiseFilterSettingsServiceTest {

    @Mock
    private AppSettingService appSettingService;

    private NoiseFilterSettingsService service;

    @BeforeEach
    void setUp() {
        // 库里没配过 → 一律返回出厂值。lenient：校验失败那几个用例根本走不到读库
        org.mockito.Mockito.lenient()
                .when(appSettingService.getOrDefault(anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(1));
        service = new NoiseFilterSettingsService(
                appSettingService, new MessageNoiseFilter(appSettingService));
    }

    private static NoiseFilterPreviewRequest draft(List<String> texts) {
        NoiseFilterPreviewRequest r = new NoiseFilterPreviewRequest();
        r.setTexts(texts);
        return r;
    }

    @Test
    void getReturnsFactoryDefaultsWhenNothingIsConfigured() {
        NoiseFilterVO vo = service.get();
        assertTrue(vo.isEnabled());
        assertEquals(TuningDefaults.DEFAULT_NOISE_KEYWORDS, vo.getKeywords());
        assertEquals(TuningDefaults.DEFAULT_NOISE_REGEX, vo.getRegex());
        assertEquals(TuningDefaults.DEFAULT_KEEP_REGEX, vo.getDefaultKeepRegex());
    }

    @Test
    void updateRejectsInvalidRegexBeforeWriting() {
        NoiseFilterUpdateRequest r = new NoiseFilterUpdateRequest();
        r.setRegex("[unclosed");
        assertThrows(BusinessException.class, () -> service.update(r));
        verify(appSettingService, never()).putAll(any());
    }

    @Test
    void updateRejectsNestedQuantifier() {
        NoiseFilterUpdateRequest r = new NoiseFilterUpdateRequest();
        r.setRegex("(a+)+$");
        BusinessException e = assertThrows(BusinessException.class, () -> service.update(r));
        assertTrue(e.getMessage().contains("嵌套量词"));
    }

    @Test
    void updateAcceptsAValidRegex() {
        NoiseFilterUpdateRequest r = new NoiseFilterUpdateRequest();
        r.setRegex("^666$");
        when(appSettingService.putAll(any())).thenReturn(1);
        when(appSettingService.getOrDefault(
                org.mockito.ArgumentMatchers.eq("noise_filter_regex"), anyString()))
                .thenReturn("^666$");
        assertEquals("^666$", service.update(r).getRegex());
        verify(appSettingService).putAll(any());
    }

    @Test
    void previewUsesUnsavedDraftRegexAndNeverWrites() {
        // 出厂正则会把「呵呵呵」判成重复字符；草稿里改成「什么都不匹配」就该保留
        NoiseFilterPreviewRequest r = draft(List.of("呵呵呵", "明天交表"));
        r.setEnabled(true);
        r.setRegex("^$");

        var items = service.preview(r.getTexts(), r);

        assertEquals(2, items.size());
        assertTrue(items.get(0).kept());
        assertTrue(items.get(1).kept());
        verify(appSettingService, never()).putAll(any());
    }

    @Test
    void previewHonorsDisabledDraft() {
        NoiseFilterPreviewRequest r = draft(List.of("哈哈哈"));
        r.setEnabled(false);

        var items = service.preview(r.getTexts(), r);

        assertTrue(items.get(0).kept(), "总开关关掉时试跑不该丢任何句子");
    }

    @Test
    void previewRejectsBrokenDraftRegexInsteadOfSilentlyIgnoringIt() {
        NoiseFilterPreviewRequest r = draft(List.of("哈哈哈"));
        r.setRegex("(");
        assertThrows(BusinessException.class, () -> service.preview(r.getTexts(), r));
    }

    @Test
    void savedRulesStillDropRepeatedCharacters() {
        var items = service.preview(List.of("呵呵呵"));
        assertFalse(items.get(0).kept());
        assertEquals("repeat", items.get(0).reason());
    }
}
