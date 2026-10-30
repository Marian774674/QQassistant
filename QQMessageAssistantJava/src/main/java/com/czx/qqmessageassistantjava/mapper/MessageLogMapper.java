package com.czx.qqmessageassistantjava.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

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
    @Select("SELECT chat_id, MAX(chat_name) AS chat_name FROM message_log "
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
}
