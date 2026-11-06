package com.czx.qqmessageassistantjava.auth;

import cn.hutool.crypto.digest.BCrypt;
import com.czx.qqmessageassistantjava.common.BusinessException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    StringRedisTemplate redis;

    @Mock
    ValueOperations<String, String> values;

    @Mock
    AppSettingService appSettingService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        authService = new AuthService(redis, appSettingService, 2, 30, "admin", "admin123");
    }

    @Test
    void createCaptchaStoresTheCodeForTwoMinutes() {
        AuthService.CaptchaChallenge challenge = authService.createCaptcha();

        assertThat(challenge.captchaId()).isNotBlank();
        assertThat(challenge.image()).startsWith("data:image/png;base64,");

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(values).set(startsWith(AuthService.CAPTCHA_PREFIX), code.capture(), eq(Duration.ofMinutes(2)));
        assertThat(code.getValue()).hasSize(4);
    }

    @Test
    void loginRejectsAnExpiredCaptcha() {
        when(values.get(AuthService.CAPTCHA_PREFIX + "cid")).thenReturn(null);

        assertThatThrownBy(() -> authService.login("admin", "admin123", "cid", "abcd"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("验证码已过期");

        verify(redis).delete(AuthService.CAPTCHA_PREFIX + "cid");
    }

    @Test
    void loginRejectsAWrongCaptcha() {
        when(values.get(AuthService.CAPTCHA_PREFIX + "cid")).thenReturn("abcd");

        assertThatThrownBy(() -> authService.login("admin", "admin123", "cid", "abce"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("验证码错误");
    }

    @Test
    void loginStoresASessionOnSuccess() {
        when(values.get(AuthService.CAPTCHA_PREFIX + "cid")).thenReturn("abcd");
        when(appSettingService.getOrDefault(eq(AuthService.USERNAME_KEY), anyString())).thenReturn("admin");
        when(appSettingService.getOrDefault(eq(AuthService.PASSWORD_HASH_KEY), anyString()))
                .thenReturn(BCrypt.hashpw("admin123", BCrypt.gensalt()));

        AuthService.LoginResult result = authService.login("admin", "admin123", "cid", "abcd");

        assertThat(result.username()).isEqualTo("admin");
        assertThat(result.token()).isNotBlank();
        verify(values).set(startsWith(AuthService.SESSION_PREFIX), eq("admin"), eq(Duration.ofMinutes(30)));
    }

    @Test
    void loginInitializesThePasswordHashOnFirstUse() {
        when(values.get(AuthService.CAPTCHA_PREFIX + "cid")).thenReturn("abcd");
        when(appSettingService.getOrDefault(eq(AuthService.USERNAME_KEY), anyString())).thenReturn("admin");
        when(appSettingService.getOrDefault(eq(AuthService.PASSWORD_HASH_KEY), anyString())).thenReturn("");

        AuthService.LoginResult result = authService.login("admin", "admin123", "cid", "abcd");

        assertThat(result.token()).isNotBlank();
        verify(appSettingService).putAll(org.mockito.ArgumentMatchers.argThat(
                saved -> saved.containsKey(AuthService.PASSWORD_HASH_KEY)));
    }

    @Test
    void validateSlidesTheSessionTtl() {
        when(values.get(AuthService.SESSION_PREFIX + "t1")).thenReturn("admin");

        assertThat(authService.validate("t1")).isEqualTo("admin");
        verify(redis).expire(AuthService.SESSION_PREFIX + "t1", Duration.ofMinutes(30));
    }

    @Test
    void validateRejectsAnUnknownToken() {
        when(values.get(AuthService.SESSION_PREFIX + "t1")).thenReturn(null);

        assertThat(authService.validate("t1")).isNull();
    }

    @Test
    void logoutDeletesTheSession() {
        authService.logout("t1");

        verify(redis).delete(AuthService.SESSION_PREFIX + "t1");
    }

    @Test
    void changePasswordRejectsAWrongOldPassword() {
        when(appSettingService.getOrDefault(eq(AuthService.USERNAME_KEY), anyString())).thenReturn("admin");
        when(appSettingService.getOrDefault(eq(AuthService.PASSWORD_HASH_KEY), anyString()))
                .thenReturn(BCrypt.hashpw("admin123", BCrypt.gensalt()));

        assertThatThrownBy(() -> authService.changePassword("wrong", "newpass"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("原密码错误");
    }
}
