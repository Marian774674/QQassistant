package com.czx.qqmessageassistantjava.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czx.qqmessageassistantjava.entity.ChatAlias;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatAliasMapper extends BaseMapper<ChatAlias> {

    /** 身份是 (chat_id, kind)，群号与个人 QQ 相同也不会串。 */
    @Select("SELECT * FROM chat_alias WHERE chat_id = #{chatId} AND kind = #{kind} LIMIT 1")
    ChatAlias find(@Param("chatId") Long chatId, @Param("kind") String kind);

    @Select("SELECT * FROM chat_alias")
    List<ChatAlias> findAll();
}
