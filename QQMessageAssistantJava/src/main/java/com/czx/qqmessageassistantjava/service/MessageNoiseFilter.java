package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.constant.AppSettingKeys;
import com.czx.qqmessageassistantjava.constant.TuningDefaults;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 喂给 LLM 之前的噪音过滤。
 *
 * <p>只影响「整理 / 夜间归纳」的输入，不删除 {@code message_log}——原始消息仍然完整保留，
 * 供查询、证据回查和审计使用。规则刻意保守：默认保留，只有明确命中
 * 「纯语气词 / 重复字符 / 纯标点」才丢弃；@我、文件、时间/事务关键词、主人别名一律放行。
 *
 * <p>配置全部走 {@code app_setting}，后续可以在前端设置页开放：
 * <ul>
 *   <li>{@code noise_filter_enabled}：总开关，默认开</li>
 *   <li>{@code noise_filter_keywords}：逗号分隔的整句噪音词</li>
 *   <li>{@code noise_filter_regex}：额外噪音正则（默认匹配 3 个以上重复字符）</li>
 *   <li>{@code noise_filter_keep_regex}：命中即保留的正则（@、问号、时间、事务关键词等）</li>
 * </ul>
 */
@Service
public class MessageNoiseFilter {

    private static final Logger log = LoggerFactory.getLogger(MessageNoiseFilter.class);

    static final String ENABLED_KEY = AppSettingKeys.NOISE_FILTER_ENABLED;
    static final String KEYWORDS_KEY = AppSettingKeys.NOISE_FILTER_KEYWORDS;
    static final String REGEX_KEY = AppSettingKeys.NOISE_FILTER_REGEX;
    static final String KEEP_REGEX_KEY = AppSettingKeys.NOISE_FILTER_KEEP_REGEX;

    static final String DEFAULT_KEYWORDS = TuningDefaults.DEFAULT_NOISE_KEYWORDS;
    static final String DEFAULT_NOISE_REGEX = TuningDefaults.DEFAULT_NOISE_REGEX;
    static final String DEFAULT_KEEP_REGEX = TuningDefaults.DEFAULT_KEEP_REGEX;

    /** 纯标点 / 纯符号 / 纯空白，含绝大多数 emoji。 */
    private static final Pattern PUNCTUATION_ONLY = Pattern.compile("^[\\p{P}\\p{S}\\s]+$");

    private final AppSettingService appSettingService;

    public MessageNoiseFilter(AppSettingService appSettingService) {
        this.appSettingService = appSettingService;
    }

    /**
     * 过滤一批消息。返回的 {@code kept} 保持原顺序；{@code reasons} 只用于日志/统计。
     */
    public FilterOutcome filter(List<MessageLog> messages) {
        if (messages == null || messages.isEmpty()) {
            return new FilterOutcome(List.of(), 0, Map.of());
        }
        Policy policy = policy();
        if (!policy.enabled()) {
            return new FilterOutcome(List.copyOf(messages), 0, Map.of());
        }

        List<MessageLog> kept = new ArrayList<>(messages.size());
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (MessageLog message : messages) {
            String reason = dropReason(message, policy);
            if (reason == null) {
                kept.add(message);
            } else {
                reasons.merge(reason, 1, Integer::sum);
            }
        }
        return new FilterOutcome(List.copyOf(kept), messages.size() - kept.size(), reasons);
    }

    /**
     * 设置页「试一试」：逐句判断会不会被丢。不带草稿参数时读库里已生效的规则。
     *
     * <p>和 {@link #filter(List)} 共用同一条判定链，避免「预览说保留、真跑却丢掉」。
     */
    public List<PreviewItem> preview(List<String> texts) {
        return preview(texts, null, null, null, null);
    }

    /**
     * 按编辑框里「还没保存」的规则试跑。四个参数为 null 时沿用已保存值，
     * 传空串是明确的「停用这条规则」——和保存后的语义一致。
     */
    public List<PreviewItem> preview(List<String> texts, Boolean enabled,
                                     String keywords, String regex, String keepRegex) {
        Policy policy = (enabled == null && keywords == null && regex == null && keepRegex == null)
                ? policy() : draftPolicy(enabled, keywords, regex, keepRegex);
        List<PreviewItem> out = new ArrayList<>();
        if (texts == null) {
            return out;
        }
        for (String text : texts) {
            if (out.size() >= TuningDefaults.NOISE_PREVIEW_MAX_TEXTS) {
                break;
            }
            String safe = text == null ? ""
                    : text.substring(0, Math.min(text.length(), TuningDefaults.NOISE_PREVIEW_TEXT_LIMIT));
            MessageLog message = new MessageLog();
            message.setMsgType("text");
            message.setContent(safe);
            String reason = policy.enabled() ? dropReason(message, policy) : null;
            out.add(new PreviewItem(safe, reason == null, reason));
        }
        return out;
    }

    /**
     * 单条文本判定，给插件的「前置过滤」用。
     *
     * <p>和 {@link #filter(List)}、{@link #preview(List)} 共用 {@code policy()} 与
     * {@code dropReason()}：规则只有一份，不会出现「整理时丢、入口却放行」或反过来。
     * 总开关关掉时一律返回 {@code null}（保留），插件侧不用再判一次开关。
     *
     * @return {@code null} 表示保留；否则是丢弃原因（keyword / repeat / punctuation / blank）
     */
    public String dropReasonForText(String text, String msgType) {
        Policy policy = policy();
        if (!policy.enabled()) {
            return null;
        }
        MessageLog message = new MessageLog();
        message.setMsgType(msgType == null || msgType.isBlank() ? "text" : msgType);
        message.setContent(text);
        return dropReason(message, policy);
    }

    /**
     * @return {@code null} 表示保留；否则返回丢弃原因（keyword / repeat / punctuation / blank）
     */
    String dropReason(MessageLog message, Policy policy) {
        if (message == null) {
            return "blank";
        }
        // 文件/图片/语音等非文本消息一律保留：正文可能是空的，但附件本身有信息
        if (message.getMsgType() != null && !"text".equalsIgnoreCase(message.getMsgType())) {
            return null;
        }

        String content = message.getContent() == null ? "" : message.getContent().trim();
        if (content.isEmpty()) {
            return "blank";
        }
        // 先判保留信号：@"我"、问号、时间/事务词、主人别名 —— 即使里面夹着 666 也不丢
        if (policy.keepPattern() != null && policy.keepPattern().matcher(content).find()) {
            return null;
        }
        for (String needle : policy.ownerNeedles()) {
            if (!needle.isBlank() && content.contains(needle)) {
                return null;
            }
        }

        String normalized = content.toLowerCase(Locale.ROOT);
        if (policy.keywords().contains(normalized)) {
            return "keyword";
        }
        if (PUNCTUATION_ONLY.matcher(content).matches()) {
            return "punctuation";
        }
        if (policy.noisePattern() != null && policy.noisePattern().matcher(normalized).matches()) {
            return "repeat";
        }
        return null;
    }

    private Policy draftPolicy(Boolean enabled, String keywords, String regex, String keepRegex) {
        Policy current = policy();
        return new Policy(
                enabled == null ? current.enabled() : enabled,
                keywords == null ? current.keywords() : parseKeywords(keywords),
                regex == null ? current.noisePattern() : compile(regex, REGEX_KEY),
                keepRegex == null ? current.keepPattern() : compile(keepRegex, KEEP_REGEX_KEY),
                // 主人别名是身份信息，不从网页草稿覆盖，避免试跑时被绕过
                current.ownerNeedles());
    }

    private Policy policy() {
        String enabledRaw = appSettingService.getOrDefault(ENABLED_KEY, "1")
                .trim().toLowerCase(Locale.ROOT);
        boolean enabled = !("0".equals(enabledRaw)
                || "false".equals(enabledRaw)
                || "off".equals(enabledRaw));
        Set<String> keywords = parseKeywords(
                appSettingService.getOrDefault(KEYWORDS_KEY, DEFAULT_KEYWORDS));
        Pattern noisePattern = compile(
                appSettingService.getOrDefault(REGEX_KEY, DEFAULT_NOISE_REGEX), REGEX_KEY);
        Pattern keepPattern = compile(
                appSettingService.getOrDefault(KEEP_REGEX_KEY, DEFAULT_KEEP_REGEX), KEEP_REGEX_KEY);
        List<String> ownerNeedles = parseNeedles(
                appSettingService.getOrDefault(AppSettingKeys.OWNER_ALIASES, ""),
                appSettingService.getOrDefault(AppSettingKeys.OWNER_SENDER_ID, ""));
        return new Policy(enabled, keywords, noisePattern, keepPattern, ownerNeedles);
    }

    private static Set<String> parseKeywords(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String keyword = part.trim().toLowerCase(Locale.ROOT);
            if (!keyword.isEmpty()) {
                out.add(keyword);
            }
        }
        return out;
    }

    private static List<String> parseNeedles(String aliases, String ownerId) {
        List<String> out = new ArrayList<>();
        if (aliases != null) {
            for (String part : aliases.split(",")) {
                String alias = part.trim();
                if (!alias.isEmpty()) {
                    out.add(alias);
                }
            }
        }
        if (ownerId != null && !ownerId.isBlank()) {
            out.add(ownerId.trim());
        }
        return out;
    }

    private static Pattern compile(String regex, String key) {
        if (regex == null || regex.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            // 用户写坏正则时退化为「这条规则不生效」，不能因此让整理/归纳整体失败
            log.warn("{} 不是合法正则，本次忽略：{}", key, regex);
            return null;
        }
    }

    public record FilterOutcome(List<MessageLog> kept, int dropped, Map<String, Integer> reasons) {
    }

    /** 试跑结果：{@code reason} 为 null 表示保留。 */
    public record PreviewItem(String text, boolean kept, String reason) {
    }

    record Policy(boolean enabled, Set<String> keywords, Pattern noisePattern,
                  Pattern keepPattern, List<String> ownerNeedles) {
    }
}
