package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czx.qqmessageassistantjava.client.DifyWorkflowClient;
import com.czx.qqmessageassistantjava.entity.ChatDigestItem;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.ChatDigestItemMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import com.czx.qqmessageassistantjava.service.ChatDigestService;
import com.czx.qqmessageassistantjava.service.ChatSettingService;
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

    /** 一次最多送这么多条给模型；再多就先只送最近的 */
    static final int MAX_MESSAGES = 200;
    /** 单条消息正文进提示词前截断，避免一小时刷屏把上下文撑爆 */
    static final int CONTENT_LIMIT = 200;
    static final int SUMMARY_LIMIT = 512;
    static final int KEYWORDS_LIMIT = 255;

    private final MessageLogMapper messageLogMapper;
    private final ChatDigestItemMapper digestItemMapper;
    private final DifyWorkflowClient difyWorkflowClient;
    private final AppSettingService appSettingService;
    private final ChatSettingService chatSettingService;
    private final TransactionTemplate transactionTemplate;

    public ChatDigestServiceImpl(MessageLogMapper messageLogMapper,
                                 ChatDigestItemMapper digestItemMapper,
                                 DifyWorkflowClient difyWorkflowClient,
                                 AppSettingService appSettingService,
                                 ChatSettingService chatSettingService,
                                 TransactionTemplate transactionTemplate) {
        this.messageLogMapper = messageLogMapper;
        this.digestItemMapper = digestItemMapper;
        this.difyWorkflowClient = difyWorkflowClient;
        this.appSettingService = appSettingService;
        this.chatSettingService = chatSettingService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public int digestChat(Long chatId, String chatName, LocalDateTime start, LocalDateTime end) {
        List<MessageLog> window = loadWindow(chatId, start, end);
        if (window.isEmpty()) {
            return 0;
        }
        Set<Long> windowIds = window.stream()
                .map(MessageLog::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<MessageLog> sent = window.size() > MAX_MESSAGES
                ? window.subList(window.size() - MAX_MESSAGES, window.size())
                : window;
        if (sent.size() < window.size()) {
            log.warn("群 {} 时间窗内 {} 条消息，只把最近 {} 条送模型", chatId, window.size(), MAX_MESSAGES);
        }

        String aliases = nullToEmpty(appSettingService.getOrDefault("owner_aliases", ""));
        String focus = nullToEmpty(appSettingService.getOrDefault("digest_focus_prompt", ""));

        Map<String, Object> outputs = difyWorkflowClient.run("digest", Map.of(
                "chat_name", nullToEmpty(chatName),
                "messages", renderMessages(sent, aliases),
                "profile", aliases,
                "focus", focus));

        List<Map<String, Object>> items = parseItems(outputs.get("items"));
        if (items == null) {
            // 解析失败宁可这一小时空着，也不要把上一轮的结果删掉
            return 0;
        }

        List<ChatDigestItem> rows = new ArrayList<>();
        for (Map<String, Object> item : items) {
            List<Long> evidence = evidenceIds(item.get("source_msg_ids"));
            if (evidence.isEmpty() || !windowIds.containsAll(evidence)) {
                log.warn("群 {} 丢弃一条整理：证据 id {} 不在本窗口 {} 内", chatId, evidence, windowIds);
                continue;
            }
            ChatDigestItem row = new ChatDigestItem();
            row.setChatId(chatId);
            row.setChatName(chatName);
            row.setPeriodStart(start);
            row.setPeriodEnd(end);
            row.setSummary(truncate(str(item.get("summary")), SUMMARY_LIMIT));
            row.setImportance(clampImportance(item.get("importance")));
            row.setKeywords(truncate(keywords(item.get("keywords")), KEYWORDS_LIMIT));
            row.setMentionsMe(asBoolean(item.get("mentions_me")));
            row.setSourceMsgIds(evidence.stream().map(String::valueOf).collect(Collectors.joining(",")));
            // 库里 created_at 默认走 MySQL 的钟（UTC），而窗口和 last_digest_seen 都走 JVM 本地钟；
            // 显式给值，两边才是同一个时间轴
            row.setCreatedAt(LocalDateTime.now());
            rows.add(row);
        }

        // 先删后插：这一小时的结果就是这些（重跑覆盖而非追加）。两条必须同事务，
        // 否则删完了插入失败，这一小时就永远空着——调度器只会往前走，不会回来补。
        transactionTemplate.executeWithoutResult(status -> {
            digestItemMapper.delete(new LambdaQueryWrapper<ChatDigestItem>()
                    .eq(ChatDigestItem::getChatId, chatId)
                    .eq(ChatDigestItem::getPeriodStart, start));
            rows.forEach(digestItemMapper::insert);
        });
        return rows.size();
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
        // 再按开关过滤一遍：即使 IN 之外还有残行（比如刚被取消勾选），也不该出现在弹窗里
        return rows.stream()
                .filter(row -> enabled.contains(row.getChatId()))
                .sorted(Comparator
                        .comparing((ChatDigestItem row) -> row.getImportance() == null ? 0 : row.getImportance())
                        .reversed()
                        .thenComparing(row -> row.getCreatedAt() == null ? LocalDateTime.MIN : row.getCreatedAt()))
                .map(ChatDigestServiceImpl::toVO)
                .toList();
    }

    private static DigestItemVO toVO(ChatDigestItem row) {
        DigestItemVO vo = new DigestItemVO();
        vo.setId(row.getId());
        vo.setChatId(row.getChatId());
        vo.setChatName(row.getChatName());
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

    /** 每行 {@code id|[@我]|昵称|HH:mm|正文}，正文里的换行会破坏行结构，先压成空格 */
    private String renderMessages(List<MessageLog> logs, String aliases) {
        List<String> needles = aliasList(aliases);
        StringBuilder sb = new StringBuilder();
        for (MessageLog m : logs) {
            String content = flatten(m.getContent());
            boolean mentionsMe = needles.stream().anyMatch(content::contains);
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
