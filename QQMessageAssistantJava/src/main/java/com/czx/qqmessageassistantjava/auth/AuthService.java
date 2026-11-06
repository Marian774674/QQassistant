package com.czx.qqmessageassistantjava.auth;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.constant.AppSettingKeys;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * 单管理员登录：图形验证码 + BCrypt 口令 + Redis 会话。
 *
 * <p>账号只存一份：用户名和口令哈希放在 {@code app_setting}，不引入用户表。
 * 课程验收如果要求多用户，再把这两个键迁到 {@code sys_user} 即可，接口形状不变。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    static final String CAPTCHA_PREFIX = "auth:captcha:";
    static final String SESSION_PREFIX = "auth:session:";
    static final String USERNAME_KEY = AppSettingKeys.AUTH_USERNAME;
    static final String PASSWORD_HASH_KEY = AppSettingKeys.AUTH_PASSWORD_HASH;

    public static final String REQUEST_USER_ATTRIBUTE = "auth.username";
    public static final String REQUEST_TOKEN_ATTRIBUTE = "auth.token";

    private final StringRedisTemplate redis;
    private final AppSettingService appSettingService;
    private final Duration captchaTtl;
    private final Duration sessionTtl;
    private final String configuredUsername;
    private final String initialPassword;

    public AuthService(StringRedisTemplate redis,
                       AppSettingService appSettingService,
                       @Value("${app.auth.captcha-ttl-minutes:2}") long captchaTtlMinutes,
                       @Value("${app.auth.session-ttl-minutes:30}") long sessionTtlMinutes,
                       @Value("${app.auth.username:admin}") String configuredUsername,
                       @Value("${app.auth.initial-password:}") String initialPassword) {
        this.redis = redis;
        this.appSettingService = appSettingService;
        this.captchaTtl = Duration.ofMinutes(captchaTtlMinutes);
        this.sessionTtl = Duration.ofMinutes(sessionTtlMinutes);
        this.configuredUsername = configuredUsername;
        this.initialPassword = initialPassword;
    }

    /** 生成一次性图形验证码；验证码正文只存 Redis，图片以 data URL 返回。 */
    public CaptchaChallenge createCaptcha() {
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(120, 40, 4, 20);
        String captchaId = IdUtil.fastSimpleUUID();
        redis.opsForValue().set(CAPTCHA_PREFIX + captchaId, captcha.getCode(), captchaTtl);
        return new CaptchaChallenge(captchaId, captcha.getImageBase64Data());
    }

    /**
     * 校验验证码和口令，成功后发一个随机 token。
     * 验证码无论对错都立刻删除，避免同一个码被重复尝试。
     */
    public LoginResult login(String username, String password, String captchaId, String captchaCode) {
        if (isBlank(username) || isBlank(password) || isBlank(captchaId) || isBlank(captchaCode)) {
            throw new BusinessException("用户名、密码、验证码不能为空");
        }

        String captchaKey = CAPTCHA_PREFIX + captchaId.trim();
        String expected = redis.opsForValue().get(captchaKey);
        redis.delete(captchaKey);
        if (expected == null) {
            throw new BusinessException("验证码已过期，请刷新");
        }
        if (!expected.equalsIgnoreCase(captchaCode.trim())) {
            throw new BusinessException("验证码错误");
        }

        Credentials credentials = credentials();
        if (!credentials.username().equals(username.trim())
                || !matches(password, credentials.hash())) {
            throw new BusinessException("用户名或密码错误");
        }

        return createSession(credentials.username());
    }

    /**
     * 签发会话：密码登录与 QQ 登录共用的唯一出口。
     * 会话里只放用户名，不含任何登录方式信息——换登录方式不该让前端分叉。
     */
    public LoginResult createSession(String username) {
        String token = IdUtil.fastSimpleUUID();
        redis.opsForValue().set(SESSION_PREFIX + token, username, sessionTtl);
        return new LoginResult(token, username);
    }

    /** 当前管理员账号名。QQ 登录不碰密码哈希，只借这个名签发会话。 */
    public String sessionUsername() {
        return appSettingService.getOrDefault(USERNAME_KEY, configuredUsername);
    }

    /** @return 有效会话返回用户名；无效或过期返回 {@code null}，并把 TTL 滑动续期。 */
    public String validate(String token) {
        if (isBlank(token)) {
            return null;
        }
        String key = SESSION_PREFIX + token;
        String username = redis.opsForValue().get(key);
        if (username == null) {
            return null;
        }
        redis.expire(key, sessionTtl);
        return username;
    }

    public void logout(String token) {
        if (!isBlank(token)) {
            redis.delete(SESSION_PREFIX + token);
        }
    }

    public void changePassword(String oldPassword, String newPassword) {
        if (isBlank(oldPassword) || isBlank(newPassword)) {
            throw new BusinessException("密码不能为空");
        }
        if (newPassword.length() < 6) {
            throw new BusinessException("新密码至少 6 位");
        }
        Credentials credentials = credentials();
        if (!matches(oldPassword, credentials.hash())) {
            throw new BusinessException("原密码错误");
        }
        appSettingService.putAll(Map.of(
                PASSWORD_HASH_KEY, BCrypt.hashpw(newPassword, BCrypt.gensalt())));
    }

    private Credentials credentials() {
        String username = appSettingService.getOrDefault(USERNAME_KEY, configuredUsername);
        String hash = appSettingService.getOrDefault(PASSWORD_HASH_KEY, "");
        if (hash.isBlank()) {
            boolean generated = isBlank(initialPassword);
            String initial = generated ? RandomUtil.randomString(12) : initialPassword;
            hash = BCrypt.hashpw(initial, BCrypt.gensalt());
            appSettingService.putAll(Map.of(USERNAME_KEY, username, PASSWORD_HASH_KEY, hash));
            if (generated) {
                log.warn("未配置 AUTH_INITIAL_PASSWORD，已生成临时登录密码：{}（账号 {}，登录后请修改）",
                        initial, username);
            } else {
                log.info("首次初始化登录账号：{}", username);
            }
        }
        return new Credentials(username, hash);
    }

    private static boolean matches(String raw, String hash) {
        try {
            return BCrypt.checkpw(raw, hash);
        } catch (IllegalArgumentException e) {
            // 库里存了坏哈希时按口令错误处理，不能把 500 抛给登录页
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record CaptchaChallenge(String captchaId, String image) {
    }

    public record LoginResult(String token, String username) {
    }

    private record Credentials(String username, String hash) {
    }
}
