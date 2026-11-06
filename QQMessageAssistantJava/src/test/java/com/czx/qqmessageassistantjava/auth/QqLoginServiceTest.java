package com.czx.qqmessageassistantjava.auth;

import com.czx.qqmessageassistantjava.client.LangBotClient;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.SendMessageRequest;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QqLoginServiceTest {

    private static final String OWNER = "123456789";
    private static final String CODE_KEY = QqLoginService.CODE_PREFIX + OWNER;
    private static final String COOLDOWN_KEY = QqLoginService.COOLDOWN_PREFIX + OWNER;

    @Mock
    StringRedisTemplate redis;

    @Mock
    ValueOperations<String, String> values;

    @Mock
    AppSettingService appSettingService;

    @Mock
    AuthService authService;

    @Mock
    LangBotClient langBotClient;

    private QqLoginService qqLoginService;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        qqLoginService = new QqLoginService(
                redis, appSettingService, authService, langBotClient, 5, 60, 5, "");
    }

    // ── 状态 ──────────────────────────────────────────────────────────────

    @Test
    void statusMasksTheOwnerQq() {
        when(appSettingService.getOrDefault(QqLoginService.OWNER_KEY, "")).thenReturn(OWNER);

        QqLoginService.Status status = qqLoginService.status();

        assertThat(status.bound()).isTrue();
        assertThat(status.maskedQq()).isEqualTo("123****89");
    }

    @Test
    void statusFallsBackToConfiguredOwnerWhenSettingIsEmpty() {
        when(appSettingService.getOrDefault(QqLoginService.OWNER_KEY, "")).thenReturn("");
        QqLoginService fromEnv = new QqLoginService(
                redis, appSettingService, authService, langBotClient, 5, 60, 5, "987654321");

        QqLoginService.Status status = fromEnv.status();

        assertThat(status.bound()).isTrue();
        assertThat(status.maskedQq()).isEqualTo("987****21");
    }

    @Test
    void statusReportsUnboundWhenNothingIsConfigured() {
        when(appSettingService.getOrDefault(QqLoginService.OWNER_KEY, "")).thenReturn("");

        assertThat(qqLoginService.status().bound()).isFalse();
    }

    // ── 发码 ──────────────────────────────────────────────────────────────

    @Test
    void sendCodeRejectsMalformedQq() {
        assertThatThrownBy(() -> qqLoginService.sendCode("12ab"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("5–12 位数字");
    }

    @Test
    void sendCodeRejectsWhenOwnerIsNotBound() {
        when(appSettingService.getOrDefault(QqLoginService.OWNER_KEY, "")).thenReturn("");

        assertThatThrownBy(() -> qqLoginService.sendCode(OWNER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("还没有绑定主人 QQ 号");
    }

    @Test
    void sendCodeRejectsSomeoneElsesQq() {
        when(appSettingService.getOrDefault(QqLoginService.OWNER_KEY, "")).thenReturn(OWNER);

        assertThatThrownBy(() -> qqLoginService.sendCode("111111111"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不是已绑定的主人账号");
        verifyNoInteractions(langBotClient);
    }

    @Test
    void sendCodeStoresSixDigitsAndPushesToTheOwnerPrivately() {
        when(appSettingService.getOrDefault(QqLoginService.OWNER_KEY, "")).thenReturn(OWNER);
        when(redis.getExpire(COOLDOWN_KEY, TimeUnit.SECONDS)).thenReturn(-2L);

        QqLoginService.SendCodeResult result = qqLoginService.sendCode(OWNER);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(CODE_KEY), code.capture(), eq(Duration.ofMinutes(5)));
        assertThat(code.getValue()).matches("\\d{6}");

        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(langBotClient).sendMessage(sent.capture());
        SendMessageRequest request = sent.getValue();
        assertThat(request.getTarget().getType()).isEqualTo("private");
        assertThat(request.getTarget().getId()).isEqualTo(Long.parseLong(OWNER));
        assertThat(request.getContent()).contains(code.getValue());
        // 登录码要立刻到，不能走拟人延迟
        assertThat(request.getDelayMs()).isZero();
        // bizRef 必须每次唯一，否则发送桥的幂等会把第二次登录码吞掉
        assertThat(request.getBizRef()).startsWith("auth-login:" + OWNER + ":");

        assertThat(result.sent()).isTrue();
        assertThat(result.expiresInSeconds()).isEqualTo(300);
        assertThat(result.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void sendCodeRespectsCooldownWithoutSendingAnything() {
        when(appSettingService.getOrDefault(QqLoginService.OWNER_KEY, "")).thenReturn(OWNER);
        when(redis.getExpire(COOLDOWN_KEY, TimeUnit.SECONDS)).thenReturn(42L);

        QqLoginService.SendCodeResult result = qqLoginService.sendCode(OWNER);

        assertThat(result.sent()).isFalse();
        assertThat(result.retryAfterSeconds()).isEqualTo(42);
        verifyNoInteractions(langBotClient);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void sendCodeDiscardsTheCodeWhenPushFails() {
        when(appSettingService.getOrDefault(QqLoginService.OWNER_KEY, "")).thenReturn(OWNER);
        when(redis.getExpire(COOLDOWN_KEY, TimeUnit.SECONDS)).thenReturn(-2L);
        when(langBotClient.sendMessage(any())).thenThrow(new BusinessException("发送桥不可用"));

        assertThatThrownBy(() -> qqLoginService.sendCode(OWNER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("验证码发送失败");

        // 收不到的码不能留着，否则页面会显示「已发送」却永远等不到
        verify(redis).delete(CODE_KEY);
        verify(values, never()).set(eq(COOLDOWN_KEY), anyString(), any(Duration.class));
    }

    // ── 校验 ──────────────────────────────────────────────────────────────

    @Test
    void verifyRejectsAnExpiredCode() {
        when(appSettingService.getOrDefault(QqLoginService.OWNER_KEY, "")).thenReturn(OWNER);
        when(values.get(CODE_KEY)).thenReturn(null);

        assertThatThrownBy(() -> qqLoginService.verify(OWNER, "123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已过期");
    }

    @Test
    void verifyRejectsABlankCode() {
        when(appSettingService.getOrDefault(QqLoginService.OWNER_KEY, "")).thenReturn(OWNER);

        assertThatThrownBy(() -> qqLoginService.verify(OWNER, "  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请输入收到的验证码");
    }

    @Test
    void verifyRejectsAWrongCodeAndCountsTheAttempt() {
        when(appSettingService.getOrDefault(QqLoginService.OWNER_KEY, "")).thenReturn(OWNER);
        when(values.get(CODE_KEY)).thenReturn("123456");
        when(values.increment(QqLoginService.ATTEMPT_PREFIX + OWNER)).thenReturn(1L);

        assertThatThrownBy(() -> qqLoginService.verify(OWNER, "654321"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("还可以试 4 次");

        verify(redis, never()).delete(CODE_KEY);
    }

    @Test
    void verifyInvalidatesTheCodeAfterTooManyAttempts() {
        when(appSettingService.getOrDefault(QqLoginService.OWNER_KEY, "")).thenReturn(OWNER);
        when(values.get(CODE_KEY)).thenReturn("123456");
        when(values.increment(QqLoginService.ATTEMPT_PREFIX + OWNER)).thenReturn(6L);

        assertThatThrownBy(() -> qqLoginService.verify(OWNER, "000000"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("错误次数过多");

        verify(redis).delete(CODE_KEY);
        verify(redis).delete(QqLoginService.ATTEMPT_PREFIX + OWNER);
    }

    @Test
    void verifyIssuesTheSameSessionAsPasswordLogin() {
        when(appSettingService.getOrDefault(QqLoginService.OWNER_KEY, "")).thenReturn(OWNER);
        when(values.get(CODE_KEY)).thenReturn("123456");
        when(values.increment(QqLoginService.ATTEMPT_PREFIX + OWNER)).thenReturn(1L);
        when(authService.sessionUsername()).thenReturn("admin");
        when(authService.createSession("admin"))
                .thenReturn(new AuthService.LoginResult("tok-1", "admin"));

        AuthService.LoginResult result = qqLoginService.verify(OWNER, "123456");

        assertThat(result.token()).isEqualTo("tok-1");
        assertThat(result.username()).isEqualTo("admin");
        // 码是一次性的：用过就删，连同冷却与计数一起清掉
        verify(redis).delete(CODE_KEY);
        verify(redis).delete(QqLoginService.ATTEMPT_PREFIX + OWNER);
        verify(redis).delete(COOLDOWN_KEY);
    }

    @Test
    void maskKeepsHeadAndTailOnly() {
        assertThat(QqLoginService.mask("123456789")).isEqualTo("123****89");
        assertThat(QqLoginService.mask("12345")).isEqualTo("1***5");
        assertThat(QqLoginService.mask("")).isEmpty();
    }

    @Test
    void sendCodeUsesTheStoredOwnerEvenWhenEnvDiffers() {
        when(appSettingService.getOrDefault(QqLoginService.OWNER_KEY, "")).thenReturn(OWNER);
        QqLoginService withStaleEnv = new QqLoginService(
                redis, appSettingService, authService, langBotClient, 5, 60, 5, "999999999");
        when(redis.getExpire(startsWith(QqLoginService.COOLDOWN_PREFIX), eq(TimeUnit.SECONDS)))
                .thenReturn(-2L);

        withStaleEnv.sendCode(OWNER);

        verify(values).set(startsWith(QqLoginService.CODE_PREFIX + OWNER), anyString(),
                eq(Duration.ofMinutes(5)));
    }
}
