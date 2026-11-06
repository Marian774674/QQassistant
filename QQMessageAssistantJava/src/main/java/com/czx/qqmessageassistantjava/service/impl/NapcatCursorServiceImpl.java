package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.entity.ChatSetting;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.entity.NapcatCursor;
import com.czx.qqmessageassistantjava.mapper.ChatSettingMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.mapper.NapcatCursorMapper;
import com.czx.qqmessageassistantjava.service.NapcatCursorService;
import com.czx.qqmessageassistantjava.vo.NapcatTargetVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class NapcatCursorServiceImpl implements NapcatCursorService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    /** 只看最近 7 天：几个月没说话的群补一遍，除了多打几十次 QQ 接口没有收益，还更容易触发风控 */
    static final int RECENT_DAYS = 7;

    private static final Set<String> KINDS = Set.of("GROUP", "PRIVATE");

    private final NapcatCursorMapper napcatCursorMapper;
    private final MessageLogMapper messageLogMapper;
    private final ChatSettingMapper chatSettingMapper;

    public NapcatCursorServiceImpl(NapcatCursorMapper napcatCursorMapper,
                                   MessageLogMapper messageLogMapper,
                                   ChatSettingMapper chatSettingMapper) {
        this.napcatCursorMapper = napcatCursorMapper;
        this.messageLogMapper = messageLogMapper;
        this.chatSettingMapper = chatSettingMapper;
    }

    @Override
    public List<NapcatTargetVO> targets(Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        LocalDateTime since = LocalDateTime.now().minusDays(RECENT_DAYS);

        // 用 LinkedHashMap 而不是 List：同一个会话既可能在 chat_setting 里开着开关、
        // 又在近 7 天有消息，两路都会带出它；这里按 (kind, chatId) 合并成一条，
        // 顺便把两路各自缺的字段补上（开关行有群名、日志行有活跃时间）。
        Map<String, Candidate> merged = new LinkedHashMap<>();
        for (ChatSetting setting : enabledSettings()) {
            if (setting.getChatId() == null) {
                continue;
            }
            merge(merged, setting.getKind(), setting.getChatId(), setting.getChatName(),
                    setting.getUpdatedAt(), true);
        }
        for (MessageLog row : messageLogMapper.selectRecentGroupPeers(since, size)) {
            if (row.getChatId() == null) {
                continue;
            }
            merge(merged, "GROUP", row.getChatId(), row.getChatName(), row.getCreatedAt(), false);
        }
        for (MessageLog row : messageLogMapper.selectRecentPrivatePeers(since, size)) {
            if (row.getSenderId() == null) {
                continue;
            }
            // 私聊没有群名：message_log 里 chat_name 一律 NULL，这里也不要拿昵称顶上去，
            // 否则补回来的日志会和实时日志长得不一样
            merge(merged, "PRIVATE", row.getSenderId(), null, row.getCreatedAt(), false);
        }

        // 用户明确开过开关的会话排前面：它们才是「掉线期间真的会漏东西」的地方，
        // 撞上 50 个上限时不能先被一堆闲聊群挤掉
        List<Candidate> ordered = new ArrayList<>(merged.values());
        ordered.sort(Comparator
                .comparing((Candidate c) -> c.enabled).reversed()
                .thenComparing(c -> c.activity == null ? LocalDateTime.MIN : c.activity,
                        Comparator.<LocalDateTime>reverseOrder())
                .thenComparing(c -> c.chatId));

        Map<String, Long> cursors = cursors();
        List<NapcatTargetVO> out = new ArrayList<>(Math.min(ordered.size(), size));
        for (Candidate c : ordered) {
            if (out.size() >= size) {
                break;
            }
            NapcatTargetVO vo = new NapcatTargetVO();
            vo.setChatType(c.kind);
            vo.setChatId(c.chatId);
            vo.setChatName(c.chatName);
            // 没有游标 = 从 0 开始：插件会取最新一页，再按 seq 往前翻
            vo.setLastMessageSeq(cursors.getOrDefault(key(c.kind, c.chatId), 0L));
            out.add(vo);
        }
        return out;
    }

    @Override
    public long advance(String chatType, Long chatId, Long lastMessageSeq) {
        String kind = normalizeKind(chatType);
        if (chatId == null) {
            throw new BusinessException("chatId 不能为空");
        }
        if (lastMessageSeq == null || lastMessageSeq < 0) {
            throw new BusinessException("lastMessageSeq 不能为负");
        }
        napcatCursorMapper.upsert(kind, chatId, lastMessageSeq, LocalDateTime.now());
        // 回读而不是直接返回入参：SQL 里是 GREATEST，实际值可能比传进来的大
        NapcatCursor row = napcatCursorMapper.selectOne(new LambdaQueryWrapper<NapcatCursor>()
                .eq(NapcatCursor::getChatType, kind)
                .eq(NapcatCursor::getChatId, chatId));
        return row == null || row.getLastMessageSeq() == null
                ? lastMessageSeq : row.getLastMessageSeq();
    }

    /** 用户已经打开自动回复或整理的会话：没消息也要补，否则开关一开就是一段空洞。 */
    private List<ChatSetting> enabledSettings() {
        return chatSettingMapper.selectList(new LambdaQueryWrapper<ChatSetting>()
                .and(w -> w.eq(ChatSetting::getAutoReplyEnabled, true)
                        .or().eq(ChatSetting::getDigestEnabled, true)));
    }

    private Map<String, Long> cursors() {
        Map<String, Long> out = new HashMap<>();
        List<NapcatCursor> rows = napcatCursorMapper.selectList(null);
        if (rows == null) {
            return out;
        }
        for (NapcatCursor row : rows) {
            if (row.getChatId() != null) {
                out.put(key(row.getChatType(), row.getChatId()),
                        row.getLastMessageSeq() == null ? 0L : row.getLastMessageSeq());
            }
        }
        return out;
    }

    private static void merge(Map<String, Candidate> merged, String kind, Long chatId,
                              String chatName, LocalDateTime activity, boolean enabled) {
        if (kind == null || chatId == null) {
            return;
        }
        String k = key(kind, chatId);
        Candidate existing = merged.get(k);
        if (existing == null) {
            merged.put(k, new Candidate(kind, chatId, blankToNull(chatName), activity, enabled));
            return;
        }
        if (existing.chatName == null) {
            existing.chatName = blankToNull(chatName);
        }
        if (existing.activity == null
                || (activity != null && activity.isAfter(existing.activity))) {
            existing.activity = activity;
        }
        existing.enabled = existing.enabled || enabled;
    }

    private static String normalizeKind(String chatType) {
        String kind = chatType == null ? "" : chatType.trim().toUpperCase();
        if (!KINDS.contains(kind)) {
            throw new BusinessException("chatType 只能是 GROUP 或 PRIVATE");
        }
        return kind;
    }

    private static String key(String kind, Long chatId) {
        return (kind == null ? "" : kind) + ":" + chatId;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static final class Candidate {
        private final String kind;
        private final Long chatId;
        private String chatName;
        private LocalDateTime activity;
        private boolean enabled;

        private Candidate(String kind, Long chatId, String chatName,
                          LocalDateTime activity, boolean enabled) {
            this.kind = kind;
            this.chatId = chatId;
            this.chatName = chatName;
            this.activity = activity;
            this.enabled = enabled;
        }
    }
}
