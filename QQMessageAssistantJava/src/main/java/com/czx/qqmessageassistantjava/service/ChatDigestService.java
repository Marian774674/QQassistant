package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.vo.DigestItemVO;

import java.time.LocalDateTime;
import java.util.List;

public interface ChatDigestService {

    /**
     * 整理 {@code [start, end)} 时间窗内某个群的消息并落库。
     *
     * @return 写入条数；窗口内没有消息时返回 0，且不会调用 Dify
     */
    int digestChat(Long chatId, String chatName, LocalDateTime start, LocalDateTime end);

    /**
     * 用户上次离开之后产生的整理结果，只含已勾选「纳入整理」的群。
     *
     * <p>排序：importance 降序，同权重按时间正序（先发生的事先读）。
     */
    List<DigestItemVO> listSince(LocalDateTime since);
}
