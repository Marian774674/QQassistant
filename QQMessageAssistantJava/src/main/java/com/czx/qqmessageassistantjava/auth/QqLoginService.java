package com.czx.qqmessageassistantjava.auth;

import cn.hutool.core.util.IdUtil;
import com.czx.qqmessageassistantjava.client.LangBotClient;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.constant.AppSettingKeys;
import com.czx.qqmessageassistantjava.dto.SendMessageRequest;
import com.czx.qqmessageassistantjava.dto.SendTarget;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * QQ 授权登录：网页只填 QQ 号，验证码由机器人私聊推送。
 *
 * <p>为什么不用 QQ 互联 OAuth：那套要求开发者实名 + <b>已备案的回调域名</b>，
 * 本项目跑在内网 localhost，拿不到也备不了案。而机器人本来就在线，
 * 「私聊发码」在体验上等价于 QQ 授权，还少一个外部依赖。
 *
 * <p>安全边界：
 * <ul>
 *   <li>只向 {@code owner_sender_id} 这一个号发码，其他 QQ 号一律拒绝——码永远到不了别人手里；</li>
 *   <li>6 位数字、5 分钟有效、用一次即删；</li>
 *   <li>60 秒冷却，防止拿登录接口当短信轰炸器；</li>
 *   <li>连续错 {@code maxAttempts} 次直接作废当前码，把爆破空间压到 5/10^6。</li>
 * </ul>
 */
@Service
public class QqLoginService {

    private static final Logger log = LoggerFactory.getLogger(QqLoginService.class);

    static final String CODE_PREFIX = "auth:qq:code:";
    static final String COOLDOWN_PREFIX = "auth:qq:cooldown:";
    static final String ATTEMPT_PREFIX = "auth:qq:attempt:";
    /** 主人 QQ 号与备忘/整理共用一个键，设置页改一次全局生效。 */
    static final String OWNER_KEY = AppSettingKeys.OWNER_SENDER_ID;

    private static final Pattern QQ_PATTERN = Pattern.compile("^\\d{5,12}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final AppSettingService appSettingService;
    private final AuthService authService;
    private final LangBotClient langBotClient;
    private final Duration codeTtl;
    private final int cooldownSeconds;
    private final int maxAttempts;
    private final String fallbackOwnerQq;

    public QqLoginService(StringRedisTemplate redis,
                          AppSettingService appSettingService,
                          AuthService authService,
                          LangBotClient langBotClient,
                          @Value("${app.auth.qq-code-ttl-minutes:5}") long codeTtlMinutes,
                          @Value("${app.auth.qq-code-cooldown-seconds:60}") int cooldownSeconds,
                          @Value("${app.auth.qq-code-max-attempts:5}") int maxAttempts,
                          @Value("${app.auth.owner-qq:}") String fallbackOwnerQq) {
        this.redis = redis;
        this.appSettingService = appSettingService;
        this.authService = authService;
        this.langBotClient = langBotClient;
        this.codeTtl = Duration.ofMinutes(codeTtlMinutes);
        this.cooldownSeconds = cooldownSeconds;
        this.maxAttempts = maxAttempts;
        this.fallbackOwnerQq = fallbackOwnerQq;
    }

    /** 登录页用它决定「QQ 登录」这一栏能不能点。 */
    public Status status() {
        String owner = ownerQq();
        return new Status(!owner.isBlank(), mask(owner));
    }

    /**
     * 发一次性登录码。
     *
     * @return 冷却期内返回 {@code sent=false} 与剩余秒数（不算错误，前端据此继续倒计时）
     */
    public SendCodeResult sendCode(String rawQq) {
        String qq = requireOwnerQq(rawQq);

        String cooldownKey = COOLDOWN_PREFIX + qq;
        long remaining = remainingSeconds(cooldownKey);
        if (remaining > 0) {
            return new SendCodeResult(false, 0, remaining);
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String codeKey = CODE_PREFIX + qq;
        redis.opsForValue().set(codeKey, code, codeTtl);
        // 每次发码都重置错误计数，否则历史失败会把新码一起拖垮
        redis.delete(ATTEMPT_PREFIX + qq);

        try {
            langBotClient.sendMessage(buildRequest(qq, code));
        } catch (RuntimeException e) {
            // 发不出去就别留下一个「查得到但收不到」的码
            redis.delete(codeKey);
            log.warn("登录验证码推送失败 qq={}", qq, e);
            throw new BusinessException("验证码发送失败，确认 QQ 机器人在线，或改用密码登录");
        }

        redis.opsForValue().set(cooldownKey, "1", Duration.ofSeconds(cooldownSeconds));
        log.info("已向主人 QQ 推送登录验证码 qq={}", mask(qq));
        return new SendCodeResult(true, (int) codeTtl.toSeconds(), cooldownSeconds);
    }

    /** 校验登录码；成功后签发与密码登录完全相同的 Redis 会话。 */
    public AuthService.LoginResult verify(String rawQq, String rawCode) {
        String qq = requireOwnerQq(rawQq);
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.isEmpty()) {
            throw new BusinessException("请输入收到的验证码");
        }

        String codeKey = CODE_PREFIX + qq;
        String expected = redis.opsForValue().get(codeKey);
        if (expected == null) {
            throw new BusinessException("验证码已过期，请重新获取");
        }

        // 先计数再比对：比对成功才清计数，失败次数才会真的累加
        String attemptKey = ATTEMPT_PREFIX + qq;
        Long attempts = redis.opsForValue().increment(attemptKey);
        redis.expire(attemptKey, codeTtl);
        if (attempts != null && attempts > maxAttempts) {
            redis.delete(codeKey);
            redis.delete(attemptKey);
            throw new BusinessException("错误次数过多，验证码已作废，请重新获取");
        }

        if (!constantTimeEquals(expected, code)) {
            long left = Math.max(0, maxAttempts - (attempts == null ? 0 : attempts));
            throw new BusinessException("验证码不对，还可以试 " + left + " 次");
        }

        redis.delete(codeKey);
        redis.delete(attemptKey);
        redis.delete(COOLDOWN_PREFIX + qq);
        log.info("QQ 验证码登录成功 qq={}", mask(qq));
        return authService.createSession(authService.sessionUsername());
    }

    private SendMessageRequest buildRequest(String qq, String code) {
        SendMessageRequest request = new SendMessageRequest();
        request.setTarget(new SendTarget("private", Long.parseLong(qq)));
        request.setContent("【值守台】登录验证码 " + code + "，"
                + codeTtl.toMinutes() + " 分钟内有效。\n"
                + "只在这个网页里输入，任何人索要都是骗子。");
        // bizRef 必须每次唯一：发送桥按 bizRef 幂等，复用键会让第二次登录收不到码
        request.setBizRef("auth-login:" + qq + ":" + IdUtil.fastSimpleUUID());
        // 登录码不是闲聊，不要拟人延迟
        request.setDelayMs(0);
        return request;
    }

    /** 校验入参 QQ 号，并确认它就是已绑定的主人号。 */
    private String requireOwnerQq(String rawQq) {
        String qq = rawQq == null ? "" : rawQq.trim();
        if (!QQ_PATTERN.matcher(qq).matches()) {
            throw new BusinessException("QQ 号应该是 5–12 位数字");
        }
        String owner = ownerQq();
        if (owner.isBlank()) {
            throw new BusinessException("还没有绑定主人 QQ 号：先用密码登录，在「设置 → 我的 QQ 号」里填上");
        }
        if (!owner.equals(qq)) {
            // 内网单用户场景不做「统一模糊成功」：明确说清比让人干等更好
            throw new BusinessException("这个 QQ 号不是已绑定的主人账号");
        }
        return qq;
    }

    private String ownerQq() {
        String stored = appSettingService.getOrDefault(OWNER_KEY, "");
        if (stored != null && !stored.isBlank()) {
            return stored.trim();
        }
        return fallbackOwnerQq == null ? "" : fallbackOwnerQq.trim();
    }

    private long remainingSeconds(String key) {
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        return ttl == null || ttl < 0 ? 0 : ttl;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /** 日志与页面都只露头尾，避免完整 QQ 号到处留痕。 */
    static String mask(String qq) {
        if (qq == null || qq.isBlank()) {
            return "";
        }
        if (qq.length() <= 5) {
            return qq.charAt(0) + "***" + qq.charAt(qq.length() - 1);
        }
        return qq.substring(0, 3) + "****" + qq.substring(qq.length() - 2);
    }

    public record Status(boolean bound, String maskedQq) {
    }

    public record SendCodeResult(boolean sent, int expiresInSeconds, long retryAfterSeconds) {
    }
}
