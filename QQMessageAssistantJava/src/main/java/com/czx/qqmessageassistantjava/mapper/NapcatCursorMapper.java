package com.czx.qqmessageassistantjava.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czx.qqmessageassistantjava.entity.NapcatCursor;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface NapcatCursorMapper extends BaseMapper<NapcatCursor> {

    /**
     * 推进游标，**只前进不后退**。
     *
     * <p>插件是并发跑的（重连补拉 + 下一轮轮询可能同时推进同一个会话），
     * 用 {@code GREATEST} 让写入变成幂等的取大值，谁后到都不会把游标改小；
     * 改小就意味着下一轮把已经补过的几百条再拉一遍。
     *
     * <p>不用「先查再写」：那中间有一个窗口，两个写入方都能看到旧值，
     * 后写的那个会把游标覆盖成更小的值。
     */
    @Insert("INSERT INTO napcat_cursor(chat_type, chat_id, last_message_seq, updated_at) "
            + "VALUES(#{chatType}, #{chatId}, #{seq}, #{now}) "
            + "ON DUPLICATE KEY UPDATE "
            + "last_message_seq = GREATEST(last_message_seq, VALUES(last_message_seq)), "
            + "updated_at = VALUES(updated_at)")
    int upsert(@Param("chatType") String chatType,
               @Param("chatId") Long chatId,
               @Param("seq") Long seq,
               @Param("now") LocalDateTime now);
}
