package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.constant.TuningDefaults;
import com.czx.qqmessageassistantjava.dto.TokenUsageRecordRequest;
import com.czx.qqmessageassistantjava.entity.TokenUsage;
import com.czx.qqmessageassistantjava.mapper.TokenUsageMapper;
import com.czx.qqmessageassistantjava.vo.TokenUsageChatVO;
import com.czx.qqmessageassistantjava.vo.TokenUsageSummaryVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Token 用量账本：只追加、只读展示。
 *
 * <p>为什么不用 Dify 自带的统计：这里要按「整理 / 归纳 / 问答 / 自动回复」分账，
 * 还要和 {@code chat_id} 对上；Dify 只按应用统计，看不出一次整理花了多少。
 *
 * <p>Model 字段是刻意留空的：Dify 的 blocking 响应不回传模型名，
 * 宁可显示「未标注」，也不猜一个写进账本。要标就由部署者在环境变量里填。
 *
 * <p>按会话分账（{@code byChat}）只认 {@code chat_id}：私聊和问答类调用没有群号，
 * 统一归到「未归属会话」，让饼图各扇之和仍等于总量；群多了则只画 Top N，
 * 其余合并成「其它会话」。账本里没有 kind 列，不同私聊对象暂时分不开。
 */
@Service
public class TokenUsageService {

    /** 和前端 {@code utils/name.ts#firstSegment} 同一套分隔符：中文逗号也要切 */
    private static final Pattern NAME_SPLIT = Pattern.compile("[，,、;；]");

    private final TokenUsageMapper mapper;

    public TokenUsageService(TokenUsageMapper mapper) {
        this.mapper = mapper;
    }

    /** @return 写入的行（1 行）；参数不合法时抛 BusinessException */
    public TokenUsage record(TokenUsageRecordRequest request) {
        if (request == null) {
            throw new BusinessException("用量不能为空");
        }
        String source = request.getSource() == null || request.getSource().isBlank()
                ? "spring" : request.getSource().trim().toLowerCase(Locale.ROOT);
        if (!source.equals("spring") && !source.equals("plugin")) {
            throw new BusinessException("source 只能是 spring 或 plugin");
        }
        String task = request.getTask() == null ? "" : request.getTask().trim();
        if (task.isEmpty() || task.length() > TuningDefaults.TOKEN_USAGE_TASK_LIMIT) {
            throw new BusinessException("task 不能为空且不超过 "
                    + TuningDefaults.TOKEN_USAGE_TASK_LIMIT + " 字");
        }
        int prompt = clamp(request.getPromptTokens());
        int completion = clamp(request.getCompletionTokens());
        int total = request.getTotalTokens() == null
                ? prompt + completion : clamp(request.getTotalTokens());

        TokenUsage row = new TokenUsage();
        row.setSource(source);
        row.setTask(task);
        row.setModel(trimTo(request.getModel(), TuningDefaults.TOKEN_USAGE_MODEL_LIMIT));
        row.setPromptTokens(prompt);
        row.setCompletionTokens(completion);
        row.setTotalTokens(total);
        row.setChatId(request.getChatId());
        row.setCreatedAt(LocalDateTime.now());
        mapper.insert(row);
        return row;
    }

    public TokenUsageSummaryVO summary(int days) {
        int window = clampDays(days);
        LocalDateTime since = LocalDateTime.now().minusDays(window);
        Map<String, Object> totals = mapper.selectTotals(since);

        TokenUsageSummaryVO vo = new TokenUsageSummaryVO();
        vo.setDays(window);
        vo.setSince(since.withNano(0).toString());
        if (totals != null) {
            vo.setCalls(number(valueOf(totals, "calls")));
            vo.setPromptTokens(number(valueOf(totals, "promptTokens")));
            vo.setCompletionTokens(number(valueOf(totals, "completionTokens")));
            vo.setTotalTokens(number(valueOf(totals, "totalTokens")));
        }
        vo.setByTask(buckets(mapper.selectByTask(since)));
        vo.setBySource(buckets(mapper.selectBySource(since)));
        vo.setByDay(buckets(mapper.selectByDay(since)));
        vo.setByChat(chatBuckets(mapper.selectByChat(since)));
        return vo;
    }

    /**
     * 把「按 chat_id 分组」的原始行收成饼图扇区。
     *
     * <p>三条口径（用户确认）：① 无 chatId 的调用合并成单独一块「未归属会话」，
     * 占比之和仍等于总量；② 真实会话只留消耗最大的 Top N，其余合并；
     * ③ 未归属 / 其它都不参与 Top N 竞争，永远各占一扇。
     */
    private static List<TokenUsageChatVO> chatBuckets(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<TokenUsageChatVO> named = new ArrayList<>();
        TokenUsageChatVO unassigned = null;
        for (Map<String, Object> row : rows) {
            long tokens = number(valueOf(row, "tokens"));
            if (tokens <= 0) {
                continue; // 0 token 画不出扇区，也不该占一个图例
            }
            TokenUsageChatVO bucket = new TokenUsageChatVO();
            bucket.setTokens(tokens);
            bucket.setCalls(number(valueOf(row, "calls")));
            Long chatId = nullableLong(valueOf(row, "chatId"));
            if (chatId == null) {
                bucket.setKind("unassigned");
                bucket.setLabel("未归属会话");
                unassigned = bucket;
                continue;
            }
            bucket.setKind("chat");
            bucket.setChatId(chatId);
            String name = firstSegment(valueOf(row, "rawName"));
            bucket.setLabel(name.isEmpty() ? "群 " + chatId : name);
            named.add(bucket);
        }
        named.sort(Comparator.comparingLong(TokenUsageChatVO::getTokens).reversed()
                .thenComparing(TokenUsageChatVO::getLabel));

        List<TokenUsageChatVO> out = new ArrayList<>();
        long otherTokens = 0;
        long otherCalls = 0;
        int otherCount = 0;
        for (int i = 0; i < named.size(); i++) {
            TokenUsageChatVO bucket = named.get(i);
            if (i < TuningDefaults.TOKEN_USAGE_CHART_TOP_N) {
                out.add(bucket);
            } else {
                otherTokens += bucket.getTokens();
                otherCalls += bucket.getCalls();
                otherCount++;
            }
        }
        if (otherCount > 0) {
            TokenUsageChatVO other = new TokenUsageChatVO();
            other.setKind("other");
            other.setLabel("其它 " + otherCount + " 个会话");
            other.setTokens(otherTokens);
            other.setCalls(otherCalls);
            out.add(other);
        }
        if (unassigned != null) {
            out.add(unassigned);
        }
        // 扇区与图例都按占比降序，颜色分配才稳定
        out.sort(Comparator.comparingLong(TokenUsageChatVO::getTokens).reversed()
                .thenComparing(TokenUsageChatVO::getLabel));
        return out;
    }

    /** chat_alias / chat_name 都可能是「张三，李四」这样的逗号串，只展示第一个 */
    private static String firstSegment(Object raw) {
        if (raw == null) {
            return "";
        }
        return NAME_SPLIT.split(raw.toString(), -1)[0].trim();
    }

    /** MySQL 驱动可能给 Long / BigInteger / BigDecimal，甚至字符串，统一收敛成可空 Long */
    private static Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 分组查出来的 Map 直接丢给前端会有两个坑：MySQL 的别名大小写随驱动/连接参数变，
     * SUM 又可能给 BigDecimal。这里统一收敛成前端约定的小写键，值保持数字原样。
     */
    private static List<Map<String, Object>> buckets(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            for (String key : new String[]{"task", "source", "day", "calls", "tokens"}) {
                Object value = valueOf(row, key);
                if (value != null) {
                    item.put(key, value);
                }
            }
            out.add(item);
        }
        return out;
    }

    private static Object valueOf(Map<String, Object> row, String key) {
        if (row == null) {
            return null;
        }
        Object direct = row.get(key);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public List<TokenUsage> recent(int days, int limit) {
        int window = clampDays(days);
        int size = limit <= 0 ? TuningDefaults.TOKEN_USAGE_DEFAULT_LIMIT
                : Math.min(limit, TuningDefaults.TOKEN_USAGE_MAX_LIMIT);
        return mapper.selectList(new LambdaQueryWrapper<TokenUsage>()
                .ge(TokenUsage::getCreatedAt, LocalDateTime.now().minusDays(window))
                .orderByDesc(TokenUsage::getId)
                .last("LIMIT " + size));
    }

    private static int clampDays(int days) {
        if (days <= 0) {
            return TuningDefaults.TOKEN_USAGE_DEFAULT_DAYS;
        }
        return Math.min(days, TuningDefaults.TOKEN_USAGE_MAX_DAYS);
    }

    private static int clamp(Integer value) {
        if (value == null || value <= 0) {
            return 0;
        }
        return Math.min(value, TuningDefaults.TOKEN_USAGE_MAX_TOKENS);
    }

    private static String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null
                : trimmed.substring(0, Math.min(trimmed.length(), max));
    }

    /** MyBatis 的 SUM 可能给 Long / BigDecimal，统一转 long，别让前端拿到怪类型 */
    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
