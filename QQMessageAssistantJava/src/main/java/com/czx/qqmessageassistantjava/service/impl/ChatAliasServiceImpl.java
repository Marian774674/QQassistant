package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.entity.ChatAlias;
import com.czx.qqmessageassistantjava.entity.Draft;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.ChatAliasMapper;
import com.czx.qqmessageassistantjava.mapper.DraftMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.ChatAliasService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class ChatAliasServiceImpl implements ChatAliasService {

    private static final int ALIAS_LIMIT = 255;

    private final ChatAliasMapper chatAliasMapper;
    private final DraftMapper draftMapper;
    private final MessageLogMapper messageLogMapper;

    public ChatAliasServiceImpl(ChatAliasMapper chatAliasMapper,
                                DraftMapper draftMapper,
                                MessageLogMapper messageLogMapper) {
        this.chatAliasMapper = chatAliasMapper;
        this.draftMapper = draftMapper;
        this.messageLogMapper = messageLogMapper;
    }

    @Override
    public List<String> aliases(Long chatId, String kind) {
        ChatAlias row = chatAliasMapper.find(chatId, kind);
        return row == null ? List.of() : parse(row.getAliases());
    }

    @Override
    public String displayName(Long chatId, String kind) {
        List<String> names = aliases(chatId, kind);
        return names.isEmpty() ? null : names.get(0);
    }

    @Override
    public Map<String, String> displayNames() {
        Map<String, String> names = new LinkedHashMap<>();
        List<ChatAlias> rows = chatAliasMapper.findAll();
        if (rows == null) {
            return names;
        }
        for (ChatAlias row : rows) {
            List<String> parsed = parse(row.getAliases());
            if (!parsed.isEmpty()) {
                names.put(ChatAliasService.key(row.getChatId(), row.getKind()), parsed.get(0));
            }
        }
        return names;
    }

    @Override
    public void saveAliases(Long chatId, String kind, String aliases) {
        requireChat(chatId, kind);
        String cleaned = truncate(String.join(",", parse(aliases)), ALIAS_LIMIT);
        ChatAlias existing = chatAliasMapper.find(chatId, kind);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            ChatAlias row = new ChatAlias();
            row.setChatId(chatId);
            row.setKind(kind);
            row.setAliases(cleaned);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            chatAliasMapper.insert(row);
            return;
        }
        existing.setAliases(cleaned);
        existing.setUpdatedAt(now);
        chatAliasMapper.updateById(existing);
    }

    @Override
    public List<ChatAlias> listAllAliases() {
        List<ChatAlias> rows = chatAliasMapper.findAll();
        return rows == null ? List.of() : rows;
    }

    @Override
    public void deleteAliases(Long chatId, String kind) {
        requireChat(chatId, kind);
        chatAliasMapper.delete(new LambdaQueryWrapper<ChatAlias>()
                .eq(ChatAlias::getChatId, chatId)
                .eq(ChatAlias::getKind, kind));
    }

    @Override
    public boolean hasAliasRow(Long chatId, String kind) {
        return chatId != null && chatAliasMapper.find(chatId, kind) != null;
    }

    @Override
    public void ensureNamingDraft(Long chatId, String kind, String suggestedName, Long senderId) {
        ensureNamingDraftInternal(chatId, kind, suggestedName, senderId);
    }

    @Override
    public int backfillNamingDrafts() {
        int created = 0;
        List<MessageLog> groups = messageLogMapper.selectGroupPeers();
        if (groups != null) {
            for (MessageLog group : groups) {
                if (ensureNamingDraftInternal(group.getChatId(), "GROUP",
                        group.getChatName(), group.getSenderId())) {
                    created++;
                }
            }
        }
        List<MessageLog> peers = messageLogMapper.selectPrivatePeers();
        if (peers != null) {
            for (MessageLog peer : peers) {
                Long peerId = peer.getSenderId();
                if (ensureNamingDraftInternal(peerId, "PRIVATE",
                        peer.getChatName(), peerId)) {
                    created++;
                }
            }
        }
        return created;
    }

    /** @return 是否真的建了草稿；已存在别名行或命名草稿时返回 false。 */
    private boolean ensureNamingDraftInternal(Long chatId, String kind,
                                              String suggestedName, Long senderId) {
        if (chatId == null) {
            return false;
        }
        boolean isGroup = "GROUP".equals(kind);
        if (chatAliasMapper.find(chatId, kind) != null || hasNamingDraft(chatId, kind)) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chatId", chatId);
        payload.put("kind", kind);
        payload.put("suggestedName", suggestedName);
        LocalDateTime now = LocalDateTime.now();
        Draft draft = new Draft();
        draft.setType("NAMING");
        draft.setStatus("PENDING");
        // draft.sender_id NOT NULL；补发旧群时可能一个发言人都查不到，退回用 group 号
        draft.setSenderId(senderId == null ? chatId : senderId);
        // 草稿的 chat_id 只有群聊能填；私聊的会话键是对方 QQ，放进 payload
        draft.setChatId(isGroup ? chatId : null);
        draft.setTitle((isGroup ? "给群 " : "给 QQ ") + chatId + " 起个名字");
        draft.setPayload(payload);
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        draftMapper.insert(draft);
        return true;
    }

    /** 任何状态的命名草稿都算「已经问过」——拒绝过的也不再追问。 */
    private boolean hasNamingDraft(Long chatId, String kind) {
        List<Draft> drafts = draftMapper.selectList(
                new LambdaQueryWrapper<Draft>().eq(Draft::getType, "NAMING"));
        if (drafts == null) {
            return false;
        }
        for (Draft draft : drafts) {
            Map<String, Object> payload = draft.getPayload();
            if (payload != null
                    && chatId.equals(payloadLong(payload.get("chatId")))
                    && kind.equals(payload.get("kind"))) {
                return true;
            }
        }
        return false;
    }

    /** 既有 Long 也可能是 Integer（Jackson 反序列化），统一收成 Long。 */
    private static Long payloadLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static void requireChat(Long chatId, String kind) {
        if (chatId == null) {
            throw new BusinessException("缺少会话号");
        }
        if (!"GROUP".equals(kind) && !"PRIVATE".equals(kind)) {
            throw new BusinessException("kind 只能是 GROUP 或 PRIVATE");
        }
    }

    /** 逗号切分、trim、丢空段、保持顺序去重。 */
    static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
