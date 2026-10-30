package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.entity.ChatSetting;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.ChatSettingMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import com.czx.qqmessageassistantjava.service.ChatSettingService;
import com.czx.qqmessageassistantjava.vo.ReplyContextVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChatSettingServiceImpl extends ServiceImpl<ChatSettingMapper, ChatSetting>
        implements ChatSettingService {

    private static final Set<String> KINDS = Set.of("GROUP", "PRIVATE");

    // 直接持有 mapper：继承来的 baseMapper 在单测里不会被 Mockito 注入，
    // 而这几个查询本来也不需要 IService 的包装
    @Autowired
    private ChatSettingMapper chatSettingMapper;

    @Autowired
    private MessageLogMapper messageLogMapper;

    @Autowired
    private AppSettingService appSettingService;

    static final String REPLY_PROMPT_KEY = "reply_prompt";
    static final String REPLY_RATE_LIMIT_KEY = "reply_rate_limit";
    static final String REPLY_ENABLED_KEY = "reply_enabled";
    static final int DEFAULT_RATE_LIMIT = 20;
    static final int RECENT_CONTENT_LIMIT = 200;
    private static final int RECENT_LIMIT_MAX = 50;

    @Override
    public List<ChatSetting> listAll() {
        List<ChatSetting> rows = chatSettingMapper.selectList(new LambdaQueryWrapper<ChatSetting>()
                .orderByAsc(ChatSetting::getKind)
                .orderByAsc(ChatSetting::getChatName));
        // 日志里见过、但还没建开关行的群也列出来（开关默认关），否则用户永远没法给新群开整理
        Set<Long> known = rows.stream().map(ChatSetting::getChatId).collect(Collectors.toSet());
        List<ChatSetting> merged = new ArrayList<>(rows);
        for (MessageLog peer : messageLogMapper.selectGroupPeers()) {
            if (peer.getChatId() == null || !known.add(peer.getChatId())) {
                continue;
            }
            merged.add(discovered(peer.getChatId(), "GROUP", "群", peer.getChatName()));
        }
        // 私聊同理：没有这一行，自动回复在私聊里永远打不开（用户也没法手建）
        for (MessageLog peer : messageLogMapper.selectPrivatePeers()) {
            if (peer.getSenderId() == null || !known.add(peer.getSenderId())) {
                continue;
            }
            merged.add(discovered(peer.getSenderId(), "PRIVATE", "QQ", peer.getChatName()));
        }
        return merged;
    }

    @Override
    public List<Long> listEnabledGroupIds() {
        return chatSettingMapper.selectList(new LambdaQueryWrapper<ChatSetting>()
                        .eq(ChatSetting::getKind, "GROUP")
                        .eq(ChatSetting::getDigestEnabled, true))
                .stream()
                .map(ChatSetting::getChatId)
                .toList();
    }

    @Override
    @Transactional
    public ChatSetting upsert(Long chatId, String kind, String chatName,
                              Boolean digestEnabled, Boolean autoReplyEnabled) {
        if (kind == null || !KINDS.contains(kind)) {
            throw new BusinessException("kind 只能是 GROUP 或 PRIVATE");
        }
        if (chatId == null) {
            throw new BusinessException("chatId 不能为空");
        }
        ChatSetting existing = chatSettingMapper.selectOne(new LambdaQueryWrapper<ChatSetting>()
                .eq(ChatSetting::getChatId, chatId)
                .eq(ChatSetting::getKind, kind));
        if (existing == null) {
            ChatSetting created = new ChatSetting();
            created.setChatId(chatId);
            created.setKind(kind);
            created.setChatName(chatName);
            created.setAutoReplyEnabled(Boolean.TRUE.equals(autoReplyEnabled));
            created.setDigestEnabled(Boolean.TRUE.equals(digestEnabled));
            save(created);
            return created;
        }
        // null 表示「这次不改这一项」，别把前端没传的开关顺手关掉
        if (chatName != null && !chatName.isBlank()) {
            existing.setChatName(chatName);
        }
        if (digestEnabled != null) {
            existing.setDigestEnabled(digestEnabled);
        }
        if (autoReplyEnabled != null) {
            existing.setAutoReplyEnabled(autoReplyEnabled);
        }
        updateById(existing);
        return existing;
    }

    @Override
    public ReplyContextVO replyContext(Long chatId, String kind, Integer limit) {
        if (chatId == null) {
            throw new BusinessException("chatId 不能为空");
        }
        String effectiveKind = kind == null || kind.isBlank() ? "GROUP" : kind.trim().toUpperCase();
        if (!KINDS.contains(effectiveKind)) {
            throw new BusinessException("kind 只能是 GROUP 或 PRIVATE");
        }
        int size = limit == null ? 15 : Math.max(1, Math.min(limit, RECENT_LIMIT_MAX));

        ChatSetting setting = chatSettingMapper.selectOne(new LambdaQueryWrapper<ChatSetting>()
                .eq(ChatSetting::getChatId, chatId)
                .eq(ChatSetting::getKind, effectiveKind));

        ReplyContextVO vo = new ReplyContextVO();
        // 两层开关相乘：总开关断掉时，任何会话都闭嘴；总开关开着也还得该会话自己打开。
        // 没建开关行 = 关：默认替主人说话是最不能默默发生的事。
        vo.setEnabled(globalReplyEnabled()
                && setting != null && Boolean.TRUE.equals(setting.getAutoReplyEnabled()));
        vo.setReplyPrompt(appSettingService.getOrDefault(REPLY_PROMPT_KEY, ""));
        vo.setRateLimit(rateLimit());
        vo.setOutLastHour(messageLogMapper.selectCount(inConversation(chatId, effectiveKind)
                .eq(MessageLog::getDirection, "OUT")
                // 只数 CHAT：把备忘确认语、拦截回复算进来，一条日更通知就能吃掉整天的额度
                .eq(MessageLog::getReplyKind, "CHAT")
                .gt(MessageLog::getCreatedAt, LocalDateTime.now().minusHours(1))));
        vo.setRecentMessages(recentMessages(chatId, effectiveKind, size));
        return vo;
    }

    /**
     * 发现行（库里还没这一条）。名字拿不到时不返回 null——前端会退化成「群 367085619」，
     * 但更糟的是整行都不出现：那样用户连开关都没得点。
     */
    private static ChatSetting discovered(Long chatId, String kind, String prefix, String name) {
        ChatSetting row = new ChatSetting();
        row.setChatId(chatId);
        row.setKind(kind);
        row.setChatName(name == null || name.isBlank() ? prefix + " " + chatId : name);
        // 发现不等于开启：替主人说话、往首页塞整理，都得用户自己勾
        row.setDigestEnabled(false);
        row.setAutoReplyEnabled(false);
        return row;
    }

    /**
     * 把这个会话限定在 message_log 里。
     *
     * <p>群聊按 {@code chat_id}；私聊的 {@code chat_id} 一律是 NULL（没有群号），
     * 会话键是对方 QQ，只能按 {@code sender_id} 找——否则私聊的限流永远是 0 条、
     * 上下文永远是空的，开关看着是开的、其实什么都没生效。
     * 两者必须分开写：群号可能恰好等于某个人的 QQ，混着查会把两个会话串起来。
     */
    private static LambdaQueryWrapper<MessageLog> inConversation(Long chatId, String kind) {
        if ("PRIVATE".equals(kind)) {
            return new LambdaQueryWrapper<MessageLog>()
                    .isNull(MessageLog::getChatId)
                    .eq(MessageLog::getSenderId, chatId);
        }
        return new LambdaQueryWrapper<MessageLog>().eq(MessageLog::getChatId, chatId);
    }

    /** 取最近 N 条再翻回正序：读起来要像一段对话，而不是一份倒序清单 */
    private List<ReplyContextVO.RecentMessage> recentMessages(Long chatId, String kind, int size) {
        List<MessageLog> rows = messageLogMapper.selectList(inConversation(chatId, kind)
                .orderByDesc(MessageLog::getCreatedAt)
                .orderByDesc(MessageLog::getId)
                .last("LIMIT " + size));
        List<ReplyContextVO.RecentMessage> out = new ArrayList<>(rows.size());
        for (MessageLog row : rows) {
            ReplyContextVO.RecentMessage item = new ReplyContextVO.RecentMessage();
            item.setSenderName(row.getSenderName());
            item.setDirection(row.getDirection());
            item.setContent(truncate(flatten(row.getContent())));
            item.setCreatedAt(row.getCreatedAt());
            out.add(item);
        }
        Collections.reverse(out);
        return out;
    }

    /**
     * 全局总开关。没配过 = 开——它是「一键闭嘴」的紧急闸，不能因为升级
     * 或新装把用户已经逐个打开的会话顺手关掉。只认明确的关闭值。
     */
    private boolean globalReplyEnabled() {
        String raw = appSettingService.getOrDefault(REPLY_ENABLED_KEY, "true").trim().toLowerCase();
        return !(raw.equals("false") || raw.equals("0") || raw.equals("no") || raw.equals("off"));
    }

    private int rateLimit() {
        String raw = appSettingService.getOrDefault(REPLY_RATE_LIMIT_KEY, "").trim();
        try {
            int value = Integer.parseInt(raw);
            return value <= 0 ? DEFAULT_RATE_LIMIT : Math.min(value, 200);
        } catch (NumberFormatException e) {
            return DEFAULT_RATE_LIMIT;
        }
    }

    private static String flatten(String content) {
        return content == null ? "" : content.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static String truncate(String value) {
        return value.length() <= RECENT_CONTENT_LIMIT
                ? value : value.substring(0, RECENT_CONTENT_LIMIT);
    }
}
