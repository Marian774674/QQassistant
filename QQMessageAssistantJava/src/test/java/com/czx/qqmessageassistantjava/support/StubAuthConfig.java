package com.czx.qqmessageassistantjava.support;

import com.czx.qqmessageassistantjava.auth.AuthService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * controller 集成测试用的鉴权桩：任何 token（含没有 token）都视为已登录。
 *
 * <p>这些测试验的是接口契约与落库行为，不是登录本身——鉴权逻辑由
 * {@code AuthServiceTest} / {@code QqLoginServiceTest} 单独覆盖。
 * 不这么做的话，5.2 引入的 {@code AuthInterceptor} 会把所有请求挡成 401，
 * 测试就再也测不到它本来要测的东西。
 *
 * <p>用 {@code @Primary} 而不是改生产开关：生产代码里不存在「关掉鉴权」的配置项，
 * 避免哪天有人误把它带到线上。
 */
@TestConfiguration
public class StubAuthConfig {

    @Bean
    @Primary
    public AuthService stubAuthService() {
        AuthService stub = mock(AuthService.class);
        // any() 也匹配 null：不带 Authorization 头的请求同样放行
        when(stub.validate(any())).thenReturn("admin");
        return stub;
    }
}
