package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czx.qqmessageassistantjava.common.FlexibleLocalDateTimeDeserializer;
import com.czx.qqmessageassistantjava.constant.AppSettingKeys;
import com.czx.qqmessageassistantjava.constant.TuningDefaults;
import com.czx.qqmessageassistantjava.entity.ChatSetting;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import com.czx.qqmessageassistantjava.service.ChatDigestService;
import com.czx.qqmessageassistantjava.service.ChatSettingService;
import com.czx.qqmessageassistantjava.vo.DigestRefreshVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 进首页时的按需整理：把「上次整理到这次」这段窗口里的事归纳一遍。
 *
 * <p>不是每次都真的跑——窗口里的消息够多、或者隔得够久，才值得花一次模型调用。
 * 两个阈值存在 {@code app_setting}，前端「群设置」里可改；没到阈值就什么都不做，
 * 窗口原地累积，下次再来时一起算。
 */
@Service
public class DigestRefreshService {

    private static final Logger log = LoggerFactory.getLogger(DigestRefreshService.class);

    /** 上次真正跑整理的时间；和用户点「都看过了」的 last_digest_seen 是两回事 */
    static final String LAST_RUN_KEY = AppSettingKeys.DIGEST_LAST_RUN;
    static final String MIN_MESSAGES_KEY = AppSettingKeys.DIGEST_REFRESH_MIN_MESSAGES;
    static final String MIN_MINUTES_KEY = AppSettingKeys.DIGEST_REFRESH_MIN_MINUTES;
    static final int DEFAULT_MIN_MESSAGES = TuningDefaults.DIGEST_REFRESH_MIN_MESSAGES;
    static final int DEFAULT_MIN_MINUTES = TuningDefaults.DIGEST_REFRESH_MIN_MINUTES;
    /** 从没整理过：按最近一天算，和 GET /api/digest 的缺省窗口一致 */
    private static final int DEFAULT_LOOKBACK_HOURS = TuningDefaults.DIGEST_LOOKBACK_HOURS;

    private final ChatDigestService chatDigestService;
    private final ChatSettingService chatSettingService;
    private final AppSettingService appSettingService;
    private final MessageLogMapper messageLogMapper;

    public DigestRefreshService(ChatDigestService chatDigestService,
                                ChatSettingService chatSettingService,
                                AppSettingService appSettingService,
                                MessageLogMapper messageLogMapper) {
        this.chatDigestService = chatDigestService;
        this.chatSettingService = chatSettingService;
        this.appSettingService = appSettingService;
        this.messageLogMapper = messageLogMapper;
    }

    public DigestRefreshVO refresh() {
        return refresh(LocalDateTime.now());
    }

    DigestRefreshVO refresh(LocalDateTime now) {
        DigestRefreshVO vo = new DigestRefreshVO();
        boolean enabled = "1".equals(appSettingService.getOrDefault(AppSettingKeys.DIGEST_ENABLED, "0"));
        vo.setEnabled(enabled);

        LocalDateTime lastRun = FlexibleLocalDateTimeDeserializer.parseFlexible(
                appSettingService.getOrDefault(LAST_RUN_KEY, ""));
        if (lastRun == null) {
            lastRun = now.minusHours(DEFAULT_LOOKBACK_HOURS);
        }
        vo.setWindowStart(lastRun);

        long minutes = Math.max(0, Duration.between(lastRun, now).toMinutes());
        long count = countMessages(lastRun, now);
        vo.setMinutesSince(minutes);
        vo.setMessageCount(count);

        // 二者任一满足就整理；严格大于，正好 100 条 / 正好 60 分钟不算
        boolean due = count > intSetting(MIN_MESSAGES_KEY, DEFAULT_MIN_MESSAGES)
                || minutes > intSetting(MIN_MINUTES_KEY, DEFAULT_MIN_MINUTES);
        if (!enabled || !due) {
            return vo;
        }

        Outcome outcome = digestEnabledGroups(lastRun, now);
        vo.setRefreshed(true);
        vo.setSaved(outcome.saved());
        // 三种情况可以推锚点：窗口里本来就没消息、至少整理出内容、窗口里全是已过滤的噪音。
        // 「全是噪音」也必须推进，否则每进一次首页都会把同一批 666/哈哈哈 重新过滤一遍；
        // 但「模型返回空 / Dify 抖动」不推——否则这一窗口会被永久跳过，
        // 首页只会显示「没什么事」，用户却明明在群里看到过消息。
        if (count == 0 || (outcome.failed() == 0
                && (outcome.saved() > 0 || outcome.noiseOnly()))) {
            appSettingService.putAll(Map.of(LAST_RUN_KEY, now.withNano(0).toString()));
        } else {
            log.warn("窗口 {} → {} 有 {} 条消息，但整理结果为空或失败，保留锚点等待重试",
                    lastRun, now, count);
        }
        return vo;
    }

    private long countMessages(LocalDateTime start, LocalDateTime end) {
        List<Long> enabled = chatSettingService.listEnabledGroupIds();
        if (enabled.isEmpty()) {
            return 0;
        }
        return messageLogMapper.selectCount(new LambdaQueryWrapper<MessageLog>()
                .in(MessageLog::getChatId, enabled)
                .eq(MessageLog::getDirection, "IN")
                .ge(MessageLog::getCreatedAt, start)
                .lt(MessageLog::getCreatedAt, end));
    }

    /** 和每小时调度同一套筛选：只整理勾了「整理」的群，一个群失败不连累别的 */
    private Outcome digestEnabledGroups(LocalDateTime start, LocalDateTime end) {
        int saved = 0;
        int failed = 0;
        boolean noiseOnly = false;
        for (ChatSetting group : chatSettingService.listAll()) {
            if (!"GROUP".equals(group.getKind()) || !Boolean.TRUE.equals(group.getDigestEnabled())) {
                continue;
            }
            try {
                ChatDigestService.DigestOutcome result = chatDigestService.digestChat(
                        group.getChatId(), group.getChatName(), start, end);
                saved += result.saved();
                noiseOnly = noiseOnly || result.noiseOnly();
            } catch (Exception e) {
                failed++;
                log.warn("整理群 {} 失败，跳过", group.getChatId(), e);
            }
        }
        return new Outcome(saved, failed, noiseOnly);
    }

    /**
     * 一轮整理的结果：写了几条、几个群失败、是否全是被噪音过滤的窗口。
     * 失败数决定锚点能不能往前推；{@code noiseOnly} 让「全噪音」也能推进，避免反复重扫。
     */
    private record Outcome(int saved, int failed, boolean noiseOnly) {
    }

    private int intSetting(String key, int fallback) {
        try {
            String raw = appSettingService.getOrDefault(key, String.valueOf(fallback));
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
