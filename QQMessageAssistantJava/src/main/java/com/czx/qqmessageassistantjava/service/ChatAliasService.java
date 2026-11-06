package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.entity.ChatAlias;

import java.util.List;
import java.util.Map;

/**
 * 会话展示名（群号/私聊对方 QQ → 用户起的名字）。
 *
 * <p>展示规则：第一个名字 > 已有群名/昵称 > 号码兜底。
 */
public interface ChatAliasService {

    /** 逗号分隔的名字，按顺序去重后返回；没有行或为空时返回空列表。 */
    List<String> aliases(Long chatId, String kind);

    /** 用于展示的第一个名字；没有别名时返回 {@code null}。 */
    String displayName(Long chatId, String kind);

    /** 全量快照，键为 {@link #key(Long, String)}，值为展示名；批量格式化出处时用。 */
    Map<String, String> displayNames();

    /** 清洗后 upsert；空串是合法值，表示用户拒绝命名。 */
    void saveAliases(Long chatId, String kind, String aliases);

    /** 记忆页用：全部会话的命名行。 */
    List<ChatAlias> listAllAliases();

    /** 记忆页用：删掉一个会话的命名行（kind 必须一起给，群号可能等于某人的 QQ）。 */
    void deleteAliases(Long chatId, String kind);

    /** 是否已有这一会话的行（包括空串的「拒绝过」）。 */
    boolean hasAliasRow(Long chatId, String kind);

    /**
     * 首次见到这一会话时发一条命名草稿；已有别名行或已有命名草稿（无论状态）时什么都不做。
     * 失败由调用方吞掉，不能影响消息入库。
     */
    void ensureNamingDraft(Long chatId, String kind, String suggestedName, Long senderId);

    /** 给库里已出现过的群和私聊对象补命名草稿；返回新建条数，重复执行是幂等的。 */
    int backfillNamingDrafts();

    static String key(Long chatId, String kind) {
        return kind + ":" + chatId;
    }
}
