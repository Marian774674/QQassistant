package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.entity.ChatSetting;
import com.czx.qqmessageassistantjava.vo.ReplyContextVO;

import java.util.List;

public interface ChatSettingService extends IService<ChatSetting> {

    /** 全部会话开关，按群名排序；前端「群设置」页用 */
    List<ChatSetting> listAll();

    /** 已开启「纳入整理」的群号；调度与 digest 查询都用它做过滤 */
    List<Long> listEnabledGroupIds();

    /**
     * 按 {@code (chatId, kind)} 建或改一行。
     *
     * @param digestEnabled      null = 不改
     * @param autoReplyEnabled   null = 不改
     */
    ChatSetting upsert(Long chatId, String kind, String chatName,
                       Boolean digestEnabled, Boolean autoReplyEnabled);

    /**
     * 自动回复的决策快照（见 {@link ReplyContextVO}）。
     *
     * @param limit 最近消息条数，clamp 1–50
     */
    ReplyContextVO replyContext(Long chatId, String kind, Integer limit);
}
