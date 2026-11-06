package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.vo.NapcatTargetVO;

import java.util.List;

/**
 * NapCat 掉线补拉的「目标 + 游标」。
 *
 * <p>补拉本身在插件里做（Spring 不直连 NapCat），这里只回答两个问题：
 * 「该补哪些会话」「已经补到哪一条」。
 */
public interface NapcatCursorService {

    /**
     * 待补拉会话：近 7 天有消息的，或用户已经开了自动回复 / 整理的。
     *
     * @param limit 上限，null 用默认值 50
     */
    List<NapcatTargetVO> targets(Integer limit);

    /**
     * 推进游标，只前进不后退。
     *
     * @return 推进后的实际游标（可能比传入值大，因为取的是历史最大值）
     */
    long advance(String chatType, Long chatId, Long lastMessageSeq);
}
