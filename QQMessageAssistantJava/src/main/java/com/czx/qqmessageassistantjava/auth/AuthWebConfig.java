package com.czx.qqmessageassistantjava.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public AuthWebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 验证码和登录本身必须匿名可访问；Agent 用 X-Agent-Token 单独鉴权
                .excludePathPatterns(
                        "/api/auth/captcha",
                        "/api/auth/login",
                        "/api/auth/qq/**",
                        "/api/agent/**",
                        "/api/health");
    }
}
