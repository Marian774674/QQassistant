package com.czx.qqmessageassistantjava.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface MessageLogMapper extends BaseMapper<MessageLog> {

    /**
     * 日志里出现过的群：只有群消息有 {@code chat_id}，私聊一律 NULL，按它就能筛出群聊。
     *
     * <p>**不要**再要求 {@code chat_name} 非空——群名能不能拿到取决于 LangBot 版本
     * 与 NapCat，实机上一条都没写进去，结果群设置页永远是 0 个群。
     * 群号才是身份，名字只是显示（拿不到时由 Service 兜底成「群 &lt;号&gt;」）。
     */
    /** 带上一位发言人的 {@code sender_id}：命名草稿的 {@code sender_id} 是 NOT NULL，
     *  启动补发拿不到人就会整批插入失败。 */
    @Select("SELECT chat_id, MAX(chat_name) AS chat_name, MIN(sender_id) AS sender_id "
            + "FROM message_log "
            + "WHERE chat_id IS NOT NULL "
            + "GROUP BY chat_id")
    List<MessageLog> selectGroupPeers();

    /**
     * 日志里出现过的私聊对象。私聊没有群号（{@code chat_id IS NULL}），
     * 会话键是对方 QQ，所以这里按 {@code sender_id} 分组、把昵称塞进 chat_name 返回。
     */
    @Select("SELECT sender_id, MAX(sender_name) AS chat_name FROM message_log "
            + "WHERE chat_id IS NULL AND sender_id IS NOT NULL "
            + "GROUP BY sender_id")
    List<MessageLog> selectPrivatePeers();

    /**
     * 近 {@code since} 以来有消息的群，按最近活跃倒序。
     *
     * <p>补拉只需要「最近还在用的会话」：把几个月没说话的群也拉一遍，
     * 除了多打几十次 QQ 接口没有任何收益，还更容易触发风控。
     * {@code created_at} 有索引（{@code idx_chat_created}），这里是范围扫描。
     */
    @Select("SELECT chat_id, MAX(chat_name) AS chat_name, MAX(created_at) AS created_at "
            + "FROM message_log "
            + "WHERE chat_id IS NOT NULL AND created_at >= #{since} "
            + "GROUP BY chat_id "
            + "ORDER BY MAX(created_at) DESC "
            + "LIMIT #{limit}")
    List<MessageLog> selectRecentGroupPeers(@Param("since") LocalDateTime since,
                                            @Param("limit") int limit);

    /** 近 {@code since} 以来有消息的私聊对象，按最近活跃倒序。 */
    @Select("SELECT sender_id, MAX(sender_name) AS chat_name, MAX(created_at) AS created_at "
            + "FROM message_log "
            + "WHERE chat_id IS NULL AND sender_id IS NOT NULL AND created_at >= #{since} "
            + "GROUP BY sender_id "
            + "ORDER BY MAX(created_at) DESC "
            + "LIMIT #{limit}")
    List<MessageLog> selectRecentPrivatePeers(@Param("since") LocalDateTime since,
                                              @Param("limit") int limit);

    /**
     * 中文全文召回（ngram）：<b>不按 sender 或会话过滤</b>——答案可以在任何人说的任何会话里。
     * 但只取 {@code direction = 'IN'}：机器人自己发出去的话不是证据（spec §3.1），
     * 否则机器人说过的一句会被当成对方说的，证据卡上还会署上对方的名字。
     * 空串 / NULL 的 content 不参与 MATCH，天然被排除。
     */
    @Select("SELECT * FROM message_log "
            + "WHERE direction = 'IN' "
            + "AND MATCH(content) AGAINST(#{q} IN NATURAL LANGUAGE MODE) "
            + "ORDER BY MATCH(content) AGAINST(#{q} IN NATURAL LANGUAGE MODE) DESC, id DESC "
            + "LIMIT #{limit}")
    List<MessageLog> searchFulltext(@Param("q") String q, @Param("limit") int limit);

    /**
     * 主体本人发的 IN 消息（{@code msg-person} 通道）：时间倒序，最多 {@code limit} 条。
     *
     * <p>这是唯一按发送人过滤的召回通道——其余通道都不带人物/会话条件，
     * 否则「答案在别人嘴里、在别的会话里」就永远找不回来。
     */
    @Select("<script>SELECT * FROM message_log "
            + "WHERE direction = 'IN' AND sender_id IN "
            + "<foreach collection=\"senderIds\" item=\"sid\" open=\"(\" separator=\",\" close=\")\">"
            + "#{sid}</foreach> "
            + "ORDER BY created_at DESC, id DESC LIMIT #{limit}</script>")
    List<MessageLog> selectBySenders(@Param("senderIds") Collection<Long> senderIds,
                                     @Param("limit") int limit);

    /**
     * 某个称呼在 IN 消息正文里作为子串出现的行数（跨全部会话）。
     * 入参走 {@link #likeEscape(String)}，否则「50%」会把「5 到 0」之类的文本也算进来。
     */
    default long countMentions(String alias) {
        return countMentionsEscaped(likeEscape(alias));
    }

    @Select("SELECT COUNT(*) FROM message_log "
            + "WHERE direction = 'IN' "
            + "AND content LIKE CONCAT('%', #{alias}, '%') ESCAPE '\\\\'")
    long countMentionsEscaped(@Param("alias") String alias);

    /** 转义 LIKE 的通配符：反斜杠、{@code %}、{@code _}。 */
    static String likeEscape(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
