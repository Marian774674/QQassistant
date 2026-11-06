package com.czx.qqmessageassistantjava.auth;

import com.czx.qqmessageassistantjava.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final QqLoginService qqLoginService;

    public AuthController(AuthService authService, QqLoginService qqLoginService) {
        this.authService = authService;
        this.qqLoginService = qqLoginService;
    }

    @GetMapping("/captcha")
    public Result<CaptchaResponse> captcha() {
        AuthService.CaptchaChallenge challenge = authService.createCaptcha();
        return Result.ok(new CaptchaResponse(challenge.captchaId(), challenge.image()));
    }

    /** 登录页据此决定「QQ 登录」是否可用，以及要不要提示先去绑定。 */
    @GetMapping("/qq/status")
    public Result<QqStatusResponse> qqStatus() {
        QqLoginService.Status status = qqLoginService.status();
        return Result.ok(new QqStatusResponse(status.bound(), status.maskedQq()));
    }

    @PostMapping("/qq/code")
    public Result<QqCodeResponse> qqCode(@RequestBody QqCodeRequest request) {
        QqLoginService.SendCodeResult result = qqLoginService.sendCode(request.qq());
        return Result.ok(new QqCodeResponse(
                result.sent(), result.expiresInSeconds(), result.retryAfterSeconds()));
    }

    @PostMapping("/qq/verify")
    public Result<LoginResponse> qqVerify(@RequestBody QqVerifyRequest request) {
        AuthService.LoginResult result = qqLoginService.verify(request.qq(), request.code());
        return Result.ok(new LoginResponse(result.token(), result.username()));
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(
                request.username(), request.password(), request.captchaId(), request.captchaCode());
        return Result.ok(new LoginResponse(result.token(), result.username()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        authService.logout((String) request.getAttribute(AuthService.REQUEST_TOKEN_ATTRIBUTE));
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<MeResponse> me(HttpServletRequest request) {
        String username = (String) request.getAttribute(AuthService.REQUEST_USER_ATTRIBUTE);
        return Result.ok(new MeResponse(username));
    }

    @PostMapping("/password")
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest request) {
        authService.changePassword(request.oldPassword(), request.newPassword());
        return Result.ok();
    }

    public record CaptchaResponse(String captchaId, String image) {
    }

    public record LoginRequest(String username, String password, String captchaId, String captchaCode) {
    }

    public record LoginResponse(String token, String username) {
    }

    public record MeResponse(String username) {
    }

    public record ChangePasswordRequest(String oldPassword, String newPassword) {
    }

    public record QqStatusResponse(boolean bound, String maskedQq) {
    }

    public record QqCodeRequest(String qq) {
    }

    public record QqCodeResponse(boolean sent, int expiresInSeconds, long retryAfterSeconds) {
    }

    public record QqVerifyRequest(String qq, String code) {
    }
}
