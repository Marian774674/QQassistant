package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.constant.AppSettingKeys;
import com.czx.qqmessageassistantjava.constant.TuningDefaults;
import com.czx.qqmessageassistantjava.dto.NoiseFilterPreviewRequest;
import com.czx.qqmessageassistantjava.dto.NoiseFilterUpdateRequest;
import com.czx.qqmessageassistantjava.vo.NoiseFilterVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 噪音过滤配置的读写入口。
 *
 * <p>为什么不直接让前端写 {@code PUT /api/settings}：
 * <ol>
 *   <li>四个键里有两条是正则，写坏了以前只会在整理时打一行 warn 然后静默失效；
 *       用户会以为「改了没保存」其实是「保存了但没生效」。这里在入口就编译一次，坏的直接 400。</li>
 *   <li>只放行这四个键，避免新分区顺带把别的表项覆盖掉。</li>
 *   <li>没配过时返回出厂值，前端输入框永远有内容，「恢复默认」也有依据。</li>
 * </ol>
 */
@Service
public class NoiseFilterSettingsService {

    /**
     * 嵌套量词（如 {@code (a+)+$}）在长文本上会指数级回溯，把整理线程整个挂住。
     * 正则语法本身合法，所以要在保存时额外挡一道。
     */
    private static final Pattern NESTED_QUANTIFIER =
            Pattern.compile("\\([^()]*[+*][^()]*\\)[+*{]");

    private final AppSettingService appSettingService;
    private final MessageNoiseFilter filter;

    public NoiseFilterSettingsService(AppSettingService appSettingService, MessageNoiseFilter filter) {
        this.appSettingService = appSettingService;
        this.filter = filter;
    }

    public NoiseFilterVO get() {
        NoiseFilterVO vo = new NoiseFilterVO();
        vo.setEnabled(enabled());
        vo.setKeywords(raw(AppSettingKeys.NOISE_FILTER_KEYWORDS,
                TuningDefaults.DEFAULT_NOISE_KEYWORDS));
        vo.setRegex(raw(AppSettingKeys.NOISE_FILTER_REGEX, TuningDefaults.DEFAULT_NOISE_REGEX));
        vo.setKeepRegex(raw(AppSettingKeys.NOISE_FILTER_KEEP_REGEX,
                TuningDefaults.DEFAULT_KEEP_REGEX));
        vo.setDefaultKeywords(TuningDefaults.DEFAULT_NOISE_KEYWORDS);
        vo.setDefaultRegex(TuningDefaults.DEFAULT_NOISE_REGEX);
        vo.setDefaultKeepRegex(TuningDefaults.DEFAULT_KEEP_REGEX);
        return vo;
    }

    @Transactional
    public NoiseFilterVO update(NoiseFilterUpdateRequest request) {
        if (request == null) {
            throw new BusinessException("配置不能为空");
        }
        Map<String, String> patch = new LinkedHashMap<>();
        if (request.getEnabled() != null) {
            patch.put(AppSettingKeys.NOISE_FILTER_ENABLED,
                    request.getEnabled() ? "true" : "false");
        }
        if (request.getKeywords() != null) {
            patch.put(AppSettingKeys.NOISE_FILTER_KEYWORDS,
                    validateKeywords(request.getKeywords()));
        }
        if (request.getRegex() != null) {
            patch.put(AppSettingKeys.NOISE_FILTER_REGEX,
                    validateRegex(request.getRegex(), "噪音正则"));
        }
        if (request.getKeepRegex() != null) {
            patch.put(AppSettingKeys.NOISE_FILTER_KEEP_REGEX,
                    validateRegex(request.getKeepRegex(), "保留正则"));
        }
        if (!patch.isEmpty()) {
            appSettingService.putAll(patch);
        }
        return get();
    }

    /** 用当前库里的规则逐句试跑；不写库、不消耗模型调用。 */
    public List<MessageNoiseFilter.PreviewItem> preview(List<String> texts) {
        return filter.preview(texts);
    }

    /**
     * 按设置页「还没保存」的草稿试跑。草稿同样要走校验（坏正则、嵌套量词直接 400），
     * 否则会出现「预览里好好的，一点保存就报错」。全程只读，不碰 app_setting。
     */
    public List<MessageNoiseFilter.PreviewItem> preview(
            List<String> texts, NoiseFilterPreviewRequest draft) {
        if (draft == null || !draft.hasDraft()) {
            return preview(texts);
        }
        NoiseFilterVO saved = get();
        String keywords = draft.getKeywords() == null
                ? saved.getKeywords() : validateKeywords(draft.getKeywords());
        String regex = draft.getRegex() == null
                ? saved.getRegex() : validateRegex(draft.getRegex(), "噪音正则");
        String keepRegex = draft.getKeepRegex() == null
                ? saved.getKeepRegex() : validateRegex(draft.getKeepRegex(), "保留正则");
        Boolean enabled = draft.getEnabled() == null ? saved.isEnabled() : draft.getEnabled();
        return filter.preview(texts, enabled, keywords, regex, keepRegex);
    }

    private boolean enabled() {
        String raw = appSettingService
                .getOrDefault(AppSettingKeys.NOISE_FILTER_ENABLED, "1")
                .trim().toLowerCase(Locale.ROOT);
        return !(raw.equals("0") || raw.equals("false") || raw.equals("off"));
    }

    /** 没存过 → 出厂值；存过空串 → 空串（用户主动停用这条规则，别悄悄填回去）。 */
    private String raw(String key, String fallback) {
        return appSettingService.getOrDefault(key, fallback);
    }

    private static String validateKeywords(String value) {
        String cleaned = value.replace("\r", "").replace("\n", "");
        if (cleaned.length() > TuningDefaults.NOISE_KEYWORDS_MAX_LENGTH) {
            throw new BusinessException("噪音词最多 "
                    + TuningDefaults.NOISE_KEYWORDS_MAX_LENGTH + " 字");
        }
        return cleaned;
    }

    private static String validateRegex(String value, String label) {
        String cleaned = value.trim();
        if (cleaned.length() > TuningDefaults.NOISE_REGEX_MAX_LENGTH) {
            throw new BusinessException(label + "最多 " + TuningDefaults.NOISE_REGEX_MAX_LENGTH + " 字");
        }
        if (cleaned.isEmpty()) {
            return "";
        }
        if (NESTED_QUANTIFIER.matcher(cleaned).find()) {
            throw new BusinessException(label + "含嵌套量词（如 (a+)+），会在长消息上卡死，请改写");
        }
        try {
            Pattern.compile(cleaned);
        } catch (PatternSyntaxException e) {
            throw new BusinessException(label + "不是合法正则：" + e.getDescription());
        }
        return cleaned;
    }
}
