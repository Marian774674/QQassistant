package com.czx.qqmessageassistantjava.constant;

import java.util.Set;

/**
 * {@code app_setting} 的全部键名。
 *
 * <p>这些字符串以前散落在 auth / 调度 / 服务 / 前端四处，改一个名字要全局搜；
 * 现在集中一处，Java 侧一律引用常量（前端仍用字面量，通过接口契约对齐）。
 *
 * <p>命名规则：能由用户在网页上改的键才进 {@link #USER_EDITABLE}；
 * 系统自己写的游标、锚点、密码哈希不进，避免误暴露成「可配置项」。
 */
public final class AppSettingKeys {

    private AppSettingKeys() {
    }

    // ---- 身份与登录 ----
    public static final String AUTH_USERNAME = "auth_username";
    public static final String AUTH_PASSWORD_HASH = "auth_password_hash";
    public static final String OWNER_SENDER_ID = "owner_sender_id";
    public static final String OWNER_ALIASES = "owner_aliases";

    // ---- 自动回复 ----
    public static final String REPLY_ENABLED = "reply_enabled";
    public static final String REPLY_PROMPT = "reply_prompt";
    public static final String REPLY_RATE_LIMIT = "reply_rate_limit";

    // ---- 噪音预过滤（5.5 已开放到设置页）----
    public static final String NOISE_FILTER_ENABLED = "noise_filter_enabled";
    public static final String NOISE_FILTER_KEYWORDS = "noise_filter_keywords";
    public static final String NOISE_FILTER_REGEX = "noise_filter_regex";
    public static final String NOISE_FILTER_KEEP_REGEX = "noise_filter_keep_regex";

    /** 设置页「消息整理」分区只写这四个键，不接受任意 key。 */
    public static final Set<String> NOISE_FILTER_KEYS = Set.of(
            NOISE_FILTER_ENABLED, NOISE_FILTER_KEYWORDS,
            NOISE_FILTER_REGEX, NOISE_FILTER_KEEP_REGEX);

    // ---- 消息整理 ----
    public static final String DIGEST_ENABLED = "digest_enabled";
    public static final String DIGEST_FOCUS_PROMPT = "digest_focus_prompt";
    public static final String DIGEST_REFRESH_MIN_MESSAGES = "digest_refresh_min_messages";
    public static final String DIGEST_REFRESH_MIN_MINUTES = "digest_refresh_min_minutes";
    /** 一次送进模型的最多条数；缺省见 TuningDefaults */
    public static final String DIGEST_MAX_MESSAGES = "digest_max_messages";
    /** 空结果最多重试几次；缺省见 TuningDefaults */
    public static final String DIGEST_MAX_ATTEMPTS = "digest_max_attempts";
    public static final String DIGEST_LAST_RUN = "last_digest_run";
    public static final String DIGEST_LAST_SEEN = "last_digest_seen";

    // ---- 夜间归纳 ----
    public static final String MEMORY_JOB_ENABLED = "memory_job_enabled";
    public static final String MEMORY_CURSOR = "memory_cursor";
    public static final String SUMMARY_FREQUENCY = "summary_frequency";

    /**
     * 前端可以通过 {@code PUT /api/settings} 写的键。
     * 不在名单里的（密码哈希、游标、锚点）由后端服务自己维护。
     *
     * <p>「群设置」页的「开启消息整理」写的是 {@link #DIGEST_ENABLED}，
     * 它和下面几个 digest 键是**同一次提交**：漏掉任何一个，整张表都会被拒
     * （{@code putAll} 是先全量校验再落库），表现成「点保存弹一句『配置键不允许从网页修改』，
     * 而且连别名、关注点一起没存上」。加键时对着前端 {@code putSettings} 的调用点核一遍。
     */
    public static final Set<String> USER_EDITABLE = Set.of(
            OWNER_SENDER_ID, OWNER_ALIASES,
            REPLY_ENABLED, REPLY_PROMPT, REPLY_RATE_LIMIT,
            NOISE_FILTER_ENABLED, NOISE_FILTER_KEYWORDS,
            NOISE_FILTER_REGEX, NOISE_FILTER_KEEP_REGEX,
            DIGEST_ENABLED, DIGEST_FOCUS_PROMPT, DIGEST_REFRESH_MIN_MESSAGES,
            DIGEST_REFRESH_MIN_MINUTES, DIGEST_MAX_MESSAGES, DIGEST_MAX_ATTEMPTS,
            SUMMARY_FREQUENCY);
}
