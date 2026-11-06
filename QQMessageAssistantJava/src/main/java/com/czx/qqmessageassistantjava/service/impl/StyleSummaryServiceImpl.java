package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czx.qqmessageassistantjava.client.DifyWorkflowClient;
import com.czx.qqmessageassistantjava.constant.AppSettingKeys;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.entity.StyleProfile;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import com.czx.qqmessageassistantjava.service.StyleProfileService;
import com.czx.qqmessageassistantjava.service.StyleSummaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风格画像定期总结的实现。
 *
 * <p>取数只用「主人自己发的 IN 消息」（{@code sender_id = owner AND direction = 'IN'}）：
 * OUT 是机器人替主人说的话，拿来学习会形成自我强化回路；别人的消息更不是主人的风格。
 *
 * <p>失败语义与夜间归纳一致：<b>不推进 {@code lastSummarizedAt}</b>。调用 Dify 抛异常、
 * 模型没产出、没有可用的消息，都保留旧画像，下一次检查还会重试。
 */
@Service
public class StyleSummaryServiceImpl implements StyleSummaryService {

    private static final Logger log = LoggerFactory.getLogger(StyleSummaryServiceImpl.class);

    static final String FREQ_KEY = AppSettingKeys.SUMMARY_FREQUENCY;
    static final String DEFAULT_FREQ = "DAILY_7D";
    static final String OWNER_KEY = AppSettingKeys.OWNER_SENDER_ID;

    /** 一次最多送多少条主人消息；再多也只是风格样本，够用即可 */
    static final int MAX_MESSAGES = 400;
    static final int CONTENT_LIMIT = 200;
    static final int PROMPT_LIMIT = 8000;

    private final StyleProfileService styleProfileService;
    private final MessageLogMapper messageLogMapper;
    private final DifyWorkflowClient difyWorkflowClient;
    private final AppSettingService appSettingService;

    public StyleSummaryServiceImpl(StyleProfileService styleProfileService,
                                   MessageLogMapper messageLogMapper,
                                   DifyWorkflowClient difyWorkflowClient,
                                   AppSettingService appSettingService) {
        this.styleProfileService = styleProfileService;
        this.messageLogMapper = messageLogMapper;
        this.difyWorkflowClient = difyWorkflowClient;
        this.appSettingService = appSettingService;
    }

    @Override
    public boolean isDue() {
        Long owner = ownerId();
        if (owner == null) {
            return false;
        }
        StyleProfile profile = styleProfileService.getBySenderId(owner);
        LocalDateTime last = profile == null ? null : profile.getLastSummarizedAt();
        if (last == null) {
            return true;
        }
        if ("WEEKLY".equals(frequency())) {
            return last.isBefore(LocalDateTime.now().minusDays(7));
        }
        // DAILY_7D / DAILY_TODAY：今天还没总结过就算到期
        return last.isBefore(LocalDate.now().atStartOfDay());
    }

    @Override
    public boolean runIfDue() {
        return isDue() && summarize();
    }

    @Override
    public boolean summarize() {
        Long owner = ownerId();
        if (owner == null) {
            log.warn("风格总结跳过：app_setting.{} 未配置主人 QQ", OWNER_KEY);
            return false;
        }
        String freq = frequency();
        List<MessageLog> messages = recentOwnerMessages(owner, windowStart(freq));
        if (messages.isEmpty()) {
            // 没有样本就不覆盖旧画像，也不推进时间：等有消息了下一轮再总结
            log.info("风格总结跳过：主人 {} 在 {} 窗口内没有可用的发言", owner, freq);
            return false;
        }

        StyleProfile current = styleProfileService.getBySenderId(owner);
        String currentPrompt = current == null ? "" : nullToEmpty(current.getProfilePrompt());

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("messages", render(messages));
        inputs.put("profile", currentPrompt);
        inputs.put("current_time", LocalDateTime.now().toString());

        Map<String, Object> outputs;
        try {
            outputs = difyWorkflowClient.run("style", inputs);
        } catch (Exception e) {
            log.warn("风格总结调用 Dify 失败，保留旧画像：{}", e.toString());
            return false;
        }
        String prompt = truncate(str(outputs.get("style_prompt")).trim(), PROMPT_LIMIT);
        if (prompt.isEmpty()) {
            log.info("风格总结没有产出有效画像，保留旧画像（sender={}）", owner);
            return false;
        }
        styleProfileService.upsert(owner, prompt);
        log.info("风格总结完成：sender={} freq={} 样本 {} 条 → 画像 {} 字",
                owner, freq, messages.size(), prompt.length());
        return true;
    }

    private Long ownerId() {
        String raw = appSettingService.getOrDefault(OWNER_KEY, "").trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            log.warn("owner_sender_id 不是合法 QQ 号：{}", raw);
            return null;
        }
    }

    private String frequency() {
        String raw = appSettingService.getOrDefault(FREQ_KEY, DEFAULT_FREQ).trim();
        return switch (raw) {
            case "DAILY_TODAY", "WEEKLY" -> raw;
            default -> DEFAULT_FREQ;
        };
    }

    private LocalDateTime windowStart(String freq) {
        LocalDateTime now = LocalDateTime.now();
        return switch (freq) {
            case "DAILY_TODAY" -> LocalDate.now().atStartOfDay();
            default -> now.minusDays(7); // DAILY_7D / WEEKLY：近 7 天
        };
    }

    /** 主人亲手发的 IN 消息，按时间正序（旧→新）；库里按 id 倒序取最近 N 条再翻转。 */
    private List<MessageLog> recentOwnerMessages(Long owner, LocalDateTime start) {
        List<MessageLog> rows = messageLogMapper.selectList(new LambdaQueryWrapper<MessageLog>()
                .eq(MessageLog::getSenderId, owner)
                .eq(MessageLog::getDirection, "IN")
                .ge(MessageLog::getCreatedAt, start)
                .orderByDesc(MessageLog::getId)
                .last("LIMIT " + MAX_MESSAGES));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<MessageLog> ordered = new ArrayList<>(rows);
        Collections.reverse(ordered);
        return ordered;
    }

    private String render(List<MessageLog> messages) {
        StringBuilder sb = new StringBuilder();
        for (MessageLog m : messages) {
            String content = flatten(m.getContent());
            if (content.isEmpty()) {
                continue;
            }
            sb.append(truncate(content, CONTENT_LIMIT)).append('\n');
        }
        return sb.toString().trim();
    }

    private String flatten(String content) {
        return content == null ? "" : content.replaceAll("[\\r\\n]+", " ").trim();
    }

    private String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private String str(Object raw) {
        return raw == null ? "" : raw.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
