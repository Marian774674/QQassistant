package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czx.qqmessageassistantjava.client.DifyWorkflowClient;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.constant.AppSettingKeys;
import com.czx.qqmessageassistantjava.constant.TuningDefaults;
import com.czx.qqmessageassistantjava.entity.ChatDigestItem;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.ChatDigestItemMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import com.czx.qqmessageassistantjava.service.ChatAliasService;
import com.czx.qqmessageassistantjava.service.ChatDigestService;
import com.czx.qqmessageassistantjava.service.ChatSettingService;
import com.czx.qqmessageassistantjava.service.MessageNoiseFilter;
import com.czx.qqmessageassistantjava.vo.DigestItemVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把一段时间窗里的群消息交给 Dify 归纳成「几条要点」，校验后落库。
 *
 * <p>分工：模型负责「哪些消息值得说、怎么说」，Java 负责「不许编造证据、不许越界」。
 */
@Service
public class ChatDigestServiceImpl implements ChatDigestService {

    private static final Logger log = LoggerFactory.getLogger(ChatDigestServiceImpl.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** 出厂值收在 TuningDefaults，运行时可由 app_setting 覆盖（见 intSetting）。 */
    static final int MAX_MESSAGES = TuningDefaults.DIGEST_MAX_MESSAGES;
    static final int MAX_ATTEMPTS = TuningDefaults.DIGEST_MAX_ATTEMPTS;
    /** 单条消息正文进提示词前截断，避免一小时刷屏把上下文撑爆 */
    static final int CONTENT_LIMIT = 200;
    static final int SUMMARY_LIMIT = 512;
    static final int KEYWORDS_LIMIT = 255;
    static final String OWNER_SENDER_ID_KEY = AppSettingKeys.OWNER_SENDER_ID;

    private final MessageLogMapper messageLogMapper;
    private final ChatDigestItemMapper digestItemMapper;
    private final DifyWorkflowClient difyWorkflowClient;
    private final AppSettingService appSettingService;
    private final ChatSettingService chatSettingService;
    private final ChatAliasService chatAliasService;
    private final TransactionTemplate transactionTemplate;
    private final MessageNoiseFilter noiseFilter;

    public ChatDigestServiceImpl(MessageLogMapper messageLogMapper,
                                 ChatDigestItemMapper digestItemMapper,
                                 DifyWorkflowClient difyWorkflowClient,
                                 AppSettingService appSettingService,
                                 ChatSettingService chatSettingService,
                                 ChatAliasService chatAliasService,
                                 TransactionTemplate transactionTemplate,
                                 MessageNoiseFilter noiseFilter) {
        this.messageLogMapper = messageLogMapper;
        this.digestItemMapper = digestItemMapper;
        this.difyWorkflowClient = difyWorkflowClient;
        this.appSettingService = appSettingService;
        this.chatSettingService = chatSettingService;
        this.chatAliasService = chatAliasService;
        this.transactionTemplate = transactionTemplate;
        this.noiseFilter = noiseFilter;
    }

    @Override
    public DigestOutcome digestChat(Long chatId, String chatName, LocalDateTime start, LocalDateTime end) {
        // 提示词与落库都用用户起的名字，聊天里看到的和首页看到的才是同一个群
        String displayName = effectiveName(chatId, "GROUP", chatName);
        List<MessageLog> window = loadWindow(chatId, start, end);
        if (window.isEmpty()) {
            return new DigestOutcome(0, false);
        }

        // 先过滤再截断：否则 200 条预算会被「666」「哈哈哈」吃掉，真正有信息的消息反而送不进去。
        MessageNoiseFilter.FilterOutcome filtered = noiseFilter.filter(window);
        List<MessageLog> kept = filtered.kept();
        if (kept.isEmpty()) {
            log.info("群 {} 时间窗内 {} 条消息全是噪音（{}），跳过本次整理",
                    chatId, window.size(), filtered.reasons());
            return new DigestOutcome(0, true);
        }
        if (filtered.dropped() > 0) {
            log.info("群 {} 噪音过滤 {} 条（{}），保留 {} 条",
                    chatId, filtered.dropped(), filtered.reasons(), kept.size());
        }

        // 证据校验只看真正可能送进模型的消息：模型不能引用被过滤掉的那部分原文
        Set<Long> windowIds = kept.stream()
                .map(MessageLog::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        int maxMessages = intSetting(AppSettingKeys.DIGEST_MAX_MESSAGES, MAX_MESSAGES,
                TuningDefaults.DIGEST_MAX_MESSAGES_MIN, TuningDefaults.DIGEST_MAX_MESSAGES_MAX);
        List<MessageLog> sent = kept.size() > maxMessages
                ? kept.subList(kept.size() - maxMessages, kept.size())
                : kept;
        if (sent.size() < kept.size()) {
            log.warn("群 {} 噪音过滤后仍有 {} 条消息，只把最近 {} 条送模型", chatId, kept.size(), maxMessages);
        }

        List<String> needles = mentionNeedles(
                appSettingService.getOrDefault(AppSettingKeys.OWNER_ALIASES, ""),
                appSettingService.getOrDefault(OWNER_SENDER_ID_KEY, ""));
        String aliases = nullToEmpty(appSettingService.getOrDefault(AppSettingKeys.OWNER_ALIASES, ""));
        String focus = nullToEmpty(appSettingService.getOrDefault(AppSettingKeys.DIGEST_FOCUS_PROMPT, ""));

        int maxAttempts = intSetting(AppSettingKeys.DIGEST_MAX_ATTEMPTS, MAX_ATTEMPTS,
                1, TuningDefaults.DIGEST_MAX_ATTEMPTS_MAX);
        List<Map<String, Object>> items = null;
        Map<String, Object> outputs = Map.of();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            outputs = runDigest(chatId, displayName, sent, aliases, focus, needles);
            items = parseItems(outputs.get("items"));
            // 空结果或解析失败都再问一次；第二次还这样，下面分别兜底/报错
            if (items != null && !items.isEmpty()) {
                break;
            }
            log.warn("群 {} 第 {} 次整理{}，窗口 {} 条消息", chatId, attempt,
                    items == null ? "返回的 items 无法解析" : "没有返回要点", sent.size());
        }
        if (items == null) {
            // 解析失败宁可这一小时空着，也不要把上一轮的结果删掉；
            // 抛给调用方，刷新逻辑据此不推进 last_digest_run，下次还会重试
            throw new BusinessException("Dify 返回的 items 无法解析，本窗口留待下次整理");
        }

        Map<Long, MessageLog> byId = kept.stream()
                .filter(m -> m.getId() != null)
                .collect(Collectors.toMap(MessageLog::getId, m -> m, (a, b) -> a));
        List<ChatDigestItem> rows = new ArrayList<>();
        for (Map<String, Object> item : items) {
            List<Long> evidence = evidenceIds(item.get("source_msg_ids"));
            if (evidence.isEmpty() || !windowIds.containsAll(evidence)) {
                log.warn("群 {} 丢弃一条整理：证据 id {} 不在本窗口 {} 内", chatId, evidence, windowIds);
                continue;
            }
            // @我 是硬约束：证据里出现主人昵称/QQ 号，就不许模型把它标成普通消息
            boolean mentions = evidence.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .anyMatch(m -> mentionsOwner(m, needles));
            int importance = clampImportance(item.get("importance"));
            if (mentions) {
                importance = Math.max(4, importance);
            }
            ChatDigestItem row = new ChatDigestItem();
            row.setChatId(chatId);
            row.setChatName(displayName);
            row.setPeriodStart(start);
            row.setPeriodEnd(end);
            row.setSummary(truncate(str(item.get("summary")), SUMMARY_LIMIT));
            row.setImportance(importance);
            row.setKeywords(truncate(keywords(item.get("keywords")), KEYWORDS_LIMIT));
            row.setMentionsMe(mentions || asBoolean(item.get("mentions_me")));
            row.setSourceMsgIds(evidence.stream().map(String::valueOf).collect(Collectors.joining(",")));
            // 库里 created_at 默认走 MySQL 的钟（UTC），而窗口和 last_digest_seen 都走 JVM 本地钟；
            // 显式给值，两边才是同一个时间轴
            row.setCreatedAt(LocalDateTime.now());
            rows.add(row);
        }
        if (rows.isEmpty()) {
            // 模型把整个窗口都判成「不值得说」时，仍落一条低优先级兜底：
            // 首页要能区分「真的没消息」和「有消息但没整理出要点」。
            rows.add(fallbackItem(chatId, displayName, start, end, sent, kept, needles, window.size()));
        }

        // 先删后插：这一小时的结果就是这些（重跑覆盖而非追加）。两条必须同事务，
        // 否则删完了插入失败，这一小时就永远空着——调度器只会往前走，不会回来补。
        transactionTemplate.executeWithoutResult(status -> {
            digestItemMapper.delete(new LambdaQueryWrapper<ChatDigestItem>()
                    .eq(ChatDigestItem::getChatId, chatId)
                    .eq(ChatDigestItem::getPeriodStart, start));
            rows.forEach(digestItemMapper::insert);
        });
        return new DigestOutcome(rows.size(), false);
    }

    @Override
    public List<DigestItemVO> listSince(LocalDateTime since) {
        List<Long> enabled = chatSettingService.listEnabledGroupIds();
        if (enabled.isEmpty()) {
            return List.of();
        }
        List<ChatDigestItem> rows = digestItemMapper.selectList(new LambdaQueryWrapper<ChatDigestItem>()
                .in(ChatDigestItem::getChatId, enabled)
                .ge(ChatDigestItem::getCreatedAt, since)
                .orderByDesc(ChatDigestItem::getImportance)
                .orderByAsc(ChatDigestItem::getCreatedAt));
        Map<String, String> aliasNames = chatAliasService.displayNames();
        // 再按开关过滤一遍：即使 IN 之外还有残行（比如刚被取消勾选），也不该出现在弹窗里
        return dropCoveredRows(rows.stream()
                        .filter(row -> enabled.contains(row.getChatId()))
                        .toList()).stream()
                .sorted(Comparator
                        .comparing((ChatDigestItem row) -> row.getImportance() == null ? 0 : row.getImportance())
                        .reversed()
                        .thenComparing(row -> row.getCreatedAt() == null ? LocalDateTime.MIN : row.getCreatedAt()))
                .map(row -> toVO(row, aliasNames))
                .toList();
    }

    /**
     * 整点任务和进首页补跑是两条独立触发路径，窗口可能重叠，同一批原文会被整理两次。
     * 证据被另一条完全覆盖时，只留覆盖更全（或同样全但更新）的那条，避免首页出现重复卡片。
     */
    private static List<ChatDigestItem> dropCoveredRows(List<ChatDigestItem> rows) {
        return rows.stream()
                .filter(row -> rows.stream().noneMatch(other -> covers(other, row)))
                .toList();
    }

    private static boolean covers(ChatDigestItem other, ChatDigestItem row) {
        if (other == row) {
            return false;
        }
        Set<Long> otherIds = sourceIdSet(other);
        Set<Long> rowIds = sourceIdSet(row);
        if (rowIds.isEmpty() || !otherIds.containsAll(rowIds)) {
            return false;
        }
        if (otherIds.size() != rowIds.size()) {
            return true;
        }
        // 证据完全一样时保留较新的那条（整点任务先写、补跑后写）
        LocalDateTime otherAt = other.getCreatedAt();
        LocalDateTime rowAt = row.getCreatedAt();
        if (otherAt == null) {
            return false;
        }
        if (rowAt == null) {
            return true;
        }
        if (!otherAt.equals(rowAt)) {
            return otherAt.isAfter(rowAt);
        }
        return other.getId() != null && row.getId() != null && other.getId() > row.getId();
    }

    private static Set<Long> sourceIdSet(ChatDigestItem row) {
        Set<Long> ids = new LinkedHashSet<>();
        String raw = row.getSourceMsgIds();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String part : raw.split(",")) {
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // 历史脏数据不参与去重判断
            }
        }
        return ids;
    }

    /** 用户起的名字优先，没有就沿用调用方传来的群名。 */
    private String effectiveName(Long chatId, String kind, String fallback) {
        String alias = chatAliasService.displayName(chatId, kind);
        return alias == null || alias.isBlank() ? fallback : alias;
    }

    private static DigestItemVO toVO(ChatDigestItem row, Map<String, String> aliasNames) {
        DigestItemVO vo = new DigestItemVO();
        vo.setId(row.getId());
        vo.setChatId(row.getChatId());
        String alias = aliasNames == null ? null
                : aliasNames.get(ChatAliasService.key(row.getChatId(), "GROUP"));
        vo.setChatName(alias != null ? alias : row.getChatName());
        vo.setSummary(row.getSummary());
        vo.setImportance(row.getImportance());
        vo.setMentionsMe(row.getMentionsMe());
        vo.setSourceMsgIds(row.getSourceMsgIds());
        vo.setPeriodEnd(row.getPeriodEnd());
        vo.setCreatedAt(row.getCreatedAt());
        return vo;
    }

    private List<MessageLog> loadWindow(Long chatId, LocalDateTime start, LocalDateTime end) {
        return messageLogMapper.selectList(new LambdaQueryWrapper<MessageLog>()
                .eq(MessageLog::getChatId, chatId)
                .eq(MessageLog::getDirection, "IN")
                .ge(MessageLog::getCreatedAt, start)
                .lt(MessageLog::getCreatedAt, end)
                .orderByAsc(MessageLog::getCreatedAt)
                .orderByAsc(MessageLog::getId));
    }

    private Map<String, Object> runDigest(Long chatId, String chatName, List<MessageLog> sent,
                                          String aliases, String focus, List<String> needles) {
        return difyWorkflowClient.run("digest", Map.of(
                "chat_name", nullToEmpty(chatName),
                "messages", renderMessages(sent, needles),
                "profile", aliases,
                "focus", focus), chatId);
    }

    /**
     * 模型没有给出任何可用要点时的兜底条目。
     *
     * <p>它不编造内容，只保证「这一时段确实有消息」这个事实能到首页；
     * 否则用户会把「模型认为都是广告」误读成「这段时间没人说话」。
     */
    private ChatDigestItem fallbackItem(Long chatId, String chatName,
                                        LocalDateTime start, LocalDateTime end,
                                        List<MessageLog> sent, List<MessageLog> kept,
                                        List<String> needles, int originalCount) {
        ChatDigestItem row = new ChatDigestItem();
        row.setChatId(chatId);
        row.setChatName(chatName);
        row.setPeriodStart(start);
        row.setPeriodEnd(end);
        row.setSummary("这段时间有 " + originalCount + " 条消息，但没有整理出需要单独提醒的要点");
        boolean mentions = kept.stream().anyMatch(m -> mentionsOwner(m, needles));
        row.setImportance(mentions ? 4 : 1);
        row.setKeywords("");
        row.setMentionsMe(mentions);
        // 证据取真正送给模型的最近 MAX_MESSAGES 条，避免 source_msg_ids 随窗口无界膨胀
        row.setSourceMsgIds(sent.stream()
                .map(MessageLog::getId)
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.joining(",")));
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    /** 每行 {@code id|[@我]|昵称|HH:mm|正文}，正文里的换行会破坏行结构，先压成空格 */
    private String renderMessages(List<MessageLog> logs, List<String> needles) {
        StringBuilder sb = new StringBuilder();
        for (MessageLog m : logs) {
            String content = flatten(m.getContent());
            boolean mentionsMe = mentionsOwner(m, needles);
            String name = nullToEmpty(m.getSenderName()).isBlank()
                    ? String.valueOf(m.getSenderId())
                    : m.getSenderName();
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(m.getId()).append('|')
                    .append(mentionsMe ? "[@我]" : "").append('|')
                    .append(name).append('|')
                    .append(m.getCreatedAt() == null ? "" : HH_MM.format(m.getCreatedAt())).append('|')
                    .append(truncate(content, CONTENT_LIMIT));
        }
        return sb.toString();
    }

    /** 返回 null 表示「拿不到可用的结果」，与「结果是空数组」区分开 */
    private List<Map<String, Object>> parseItems(Object raw) {
        if (raw == null) {
            log.warn("Dify 没有返回 items");
            return null;
        }
        if (raw instanceof Collection<?> collection) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Object o : collection) {
                if (o instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    items.add(typed);
                }
            }
            return items;
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return List.of();
        }
        try {
            return JSON.readValue(text, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            log.warn("Dify 返回的 items 不是合法 JSON：{}", text, e);
            return null;
        }
    }

    private List<Long> evidenceIds(Object raw) {
        List<Long> ids = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object o : collection) {
                Long id = toLong(o);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private Long toLong(Object o) {
        if (o instanceof Number number) {
            return number.longValue();
        }
        try {
            return o == null ? null : Long.valueOf(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int clampImportance(Object raw) {
        Long value = toLong(raw);
        if (value == null) {
            return 3;
        }
        return (int) Math.max(1, Math.min(5, value));
    }

    private Boolean asBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        return raw != null && "true".equalsIgnoreCase(raw.toString().trim());
    }

    private String keywords(Object raw) {
        if (raw instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        return raw == null ? "" : raw.toString();
    }

    private List<String> aliasList(String aliases) {
        return List.of(aliases.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 昵称别名 + 主人 QQ 号；群里 @ 的常常是 QQ 号，只看别名会漏。 */
    private List<String> mentionNeedles(String aliases, String ownerSenderId) {
        List<String> needles = new ArrayList<>(aliasList(nullToEmpty(aliases)));
        String ownerId = nullToEmpty(ownerSenderId).trim();
        if (!ownerId.isEmpty() && !needles.contains(ownerId)) {
            needles.add(ownerId);
        }
        return needles;
    }

    private boolean mentionsOwner(MessageLog m, List<String> needles) {
        String content = m.getContent();
        return content != null && needles.stream().anyMatch(content::contains);
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

    /**
     * 从 app_setting 读一个整数阈值。没配 / 写坏了 / 越界一律回落到出厂值，
     * 保证「用户在网页上填了个错别字」不会让整理整体停摆。
     */
    private int intSetting(String key, int fallback, int min, int max) {
        try {
            String raw = appSettingService.getOrDefault(key, String.valueOf(fallback));
            int value = Integer.parseInt(raw.trim());
            return value < min || value > max ? fallback : value;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
