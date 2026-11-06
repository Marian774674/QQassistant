package com.czx.qqmessageassistantjava.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czx.qqmessageassistantjava.entity.TokenUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface TokenUsageMapper extends BaseMapper<TokenUsage> {

    @Select("SELECT COUNT(*) AS calls, "
            + "COALESCE(SUM(prompt_tokens), 0) AS promptTokens, "
            + "COALESCE(SUM(completion_tokens), 0) AS completionTokens, "
            + "COALESCE(SUM(total_tokens), 0) AS totalTokens "
            + "FROM token_usage WHERE created_at >= #{since}")
    Map<String, Object> selectTotals(@Param("since") LocalDateTime since);

    @Select("SELECT task, COUNT(*) AS calls, COALESCE(SUM(total_tokens), 0) AS tokens "
            + "FROM token_usage WHERE created_at >= #{since} "
            + "GROUP BY task ORDER BY tokens DESC, task")
    List<Map<String, Object>> selectByTask(@Param("since") LocalDateTime since);

    @Select("SELECT source, COUNT(*) AS calls, COALESCE(SUM(total_tokens), 0) AS tokens "
            + "FROM token_usage WHERE created_at >= #{since} "
            + "GROUP BY source ORDER BY tokens DESC, source")
    List<Map<String, Object>> selectBySource(@Param("since") LocalDateTime since);

    @Select("SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS day, "
            + "COUNT(*) AS calls, COALESCE(SUM(total_tokens), 0) AS tokens "
            + "FROM token_usage WHERE created_at >= #{since} "
            + "GROUP BY day ORDER BY day")
    List<Map<String, Object>> selectByDay(@Param("since") LocalDateTime since);

    /**
     * 按群号分账，顺手把展示名带出来（「花在哪个会话」饼图）。
     *
     * <p>non-null {@code chat_id} 一律是群号：Spring 的 digest 传群号、私聊 / 问答类传 null，
     * 插件只在群消息里带 chat_id。所以只 JOIN {@code kind='GROUP'} 的命名行，
     * 不会把「群号恰好等于某人 QQ」的私聊别名串进来。
     *
     * <p>{@code aliases} / {@code chat_name} 都可能是逗号串，统一在 Java 侧取第一段，
     * SQL 不做字符串切分。MAX() 只为满足 ONLY_FULL_GROUP_BY：两张表都有
     * {@code (chat_id, kind)} 唯一键，一个 chat_id 最多命中一行。
     */
    @Select("SELECT u.chat_id AS chatId, "
            + "COUNT(*) AS calls, "
            + "COALESCE(SUM(u.total_tokens), 0) AS tokens, "
            + "MAX(COALESCE(NULLIF(a.aliases, ''), cs.chat_name)) AS rawName "
            + "FROM token_usage u "
            + "LEFT JOIN chat_alias a ON a.chat_id = u.chat_id AND a.kind = 'GROUP' "
            + "LEFT JOIN chat_setting cs ON cs.chat_id = u.chat_id AND cs.kind = 'GROUP' "
            + "WHERE u.created_at >= #{since} "
            + "GROUP BY u.chat_id "
            + "ORDER BY tokens DESC, u.chat_id")
    List<Map<String, Object>> selectByChat(@Param("since") LocalDateTime since);
}
