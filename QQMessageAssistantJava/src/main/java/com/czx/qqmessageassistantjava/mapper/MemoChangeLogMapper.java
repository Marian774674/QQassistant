package com.czx.qqmessageassistantjava.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czx.qqmessageassistantjava.entity.MemoChangeLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MemoChangeLogMapper extends BaseMapper<MemoChangeLog> {

    /**
     * 占位写入：靠 {@code uk_event} 唯一索引把重复请求挡在业务写入之前。
     * 返回 0 表示这个 eventId 已经处理过（重放），调用方直接读旧结果返回。
     */
    @Insert("INSERT IGNORE INTO memo_change_log "
            + "(memo_id, sender_id, source, op, event_id, conversation_id, created_at) "
            + "VALUES (#{memoId}, #{senderId}, #{source}, #{op}, #{eventId}, #{conversationId}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIgnore(MemoChangeLog log);

    @Select("SELECT * FROM memo_change_log WHERE event_id = #{eventId}")
    MemoChangeLog selectByEventId(@Param("eventId") String eventId);
}
