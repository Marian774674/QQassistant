package com.czx.qqmessageassistantjava.constant;

/**
 * 调优用的数值 / 字符串默认值，集中一处。
 *
 * <p>原则是「能配的走 app_setting，不能配的才是常量」：
 * {@link AppSettingKeys} 里登记了可覆盖的键，这里给的是没配时的出厂值。
 * 想改行为先找键，找不到再改这里——不要在各个 Service 里再写一遍魔法数字。
 */
public final class TuningDefaults {

    private TuningDefaults() {
    }

    // ---- 消息整理 ----
    /** 一次最多送多少条给模型；再多先只送最近的 */
    public static final int DIGEST_MAX_MESSAGES = 200;
    public static final int DIGEST_MAX_MESSAGES_MIN = 20;
    public static final int DIGEST_MAX_MESSAGES_MAX = 1000;
    /** 模型偶尔对非空输入返回 []，空结果再问一次 */
    public static final int DIGEST_MAX_ATTEMPTS = 2;
    public static final int DIGEST_MAX_ATTEMPTS_MAX = 5;
    /** 进首页补整理的触发阈值（严格大于才算到期） */
    public static final int DIGEST_REFRESH_MIN_MESSAGES = 100;
    public static final int DIGEST_REFRESH_MIN_MINUTES = 60;
    /** 从没整理过时按最近一天算，和 GET /api/digest 缺省窗口一致 */
    public static final int DIGEST_LOOKBACK_HOURS = 24;

    // ---- 自动回复 ----
    public static final int DEFAULT_REPLY_RATE_LIMIT = 20;
    public static final int RECENT_CONTENT_LIMIT = 200;
    public static final int RECENT_LIMIT_MAX = 50;

    // ---- 噪音预过滤 ----
    /** 只收「整句就是语气词」的短句，不做长度阈值，避免误伤「走」「好」这类有效回复 */
    public static final String DEFAULT_NOISE_KEYWORDS =
            "666,6666,哈哈哈,哈哈哈哈,哈哈,笑死,笑死我了,233,2333,绝了,牛,牛逼,收到,好的,嗯嗯,哦哦,额";
    /** 三个及以上相同字符：666、哈哈哈、..........；两字「哈哈」由关键词兜底 */
    public static final String DEFAULT_NOISE_REGEX = "^(.)\\1{2,}$";
    public static final String DEFAULT_KEEP_REGEX =
            "@|＠|\\?|？|文件|图片|截图|作业|考试|班会|开会|截止|报名|交表|"
                    + "明[天日]|今[天日]|周[一二三四五六日天]|\\d{1,2}[:：]\\d{2}|"
                    + "\\d{1,2}月\\d{1,2}[日号]|生日|学号|姓名|专业|班级|电话|地址";
    /** 设置页输入长度上限，防止把整个 app_setting 撑爆 */
    public static final int NOISE_KEYWORDS_MAX_LENGTH = 2000;
    public static final int NOISE_REGEX_MAX_LENGTH = 1000;
    /** 试跑一次最多几句话 */
    public static final int NOISE_PREVIEW_MAX_TEXTS = 20;
    public static final int NOISE_PREVIEW_TEXT_LIMIT = 200;

    // ---- Token 账本 ----
    public static final int TOKEN_USAGE_DEFAULT_DAYS = 7;
    public static final int TOKEN_USAGE_MAX_DAYS = 90;
    public static final int TOKEN_USAGE_DEFAULT_LIMIT = 50;
    public static final int TOKEN_USAGE_MAX_LIMIT = 200;
    public static final int TOKEN_USAGE_TASK_LIMIT = 32;
    public static final int TOKEN_USAGE_MODEL_LIMIT = 64;
    public static final int TOKEN_USAGE_SOURCE_LIMIT = 12;
    /** 单次 token 数的合理上界，挡住 Dify 返回异常值 */
    public static final int TOKEN_USAGE_MAX_TOKENS = 10_000_000;
    /** 「花在哪个会话」饼图最多画几个真实会话，其余合并成「其它会话」 */
    public static final int TOKEN_USAGE_CHART_TOP_N = 6;
}
