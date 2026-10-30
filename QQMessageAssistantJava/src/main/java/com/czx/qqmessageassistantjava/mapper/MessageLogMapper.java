package com.czx.qqmessageassistantjava.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MessageLogMapper extends BaseMapper<MessageLog> {

    /**
     * 日志里出现过的群：插件只给群消息写 chat_name，所以这条能筛出群聊。
     * 用来给「群设置」页列出还没建开关行的群，不要求它们已经被勾选。
     */
    @Select("SELECT chat_id, MAX(chat_name) AS chat_name FROM message_log "
            + "WHERE chat_id IS NOT NULL AND chat_name IS NOT NULL AND chat_name <> '' "
            + "GROUP BY chat_id")
    List<MessageLog> selectGroupPeers();
}
