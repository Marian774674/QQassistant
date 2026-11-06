package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.vo.DigestItemVO;

import java.time.LocalDateTime;
import java.util.List;

public interface ChatDigestService {

    /**
     * 一次整理的结果。
     *
     * @param saved     写入条数
     * @param noiseOnly 窗口里有消息，但全部被噪音过滤掉了（没有调用 Dify）。
     *                  调用方据此可以推进时间锚点：这批消息已经「处理过」，不是失败。
     *                  与「模型返回空、需要重试」严格区分。
     */
    record DigestOutcome(int saved, boolean noiseOnly) {
    }

    /**
     * 整理 {@code [start, end)} 时间窗内某个群的消息并落库。
     *
     * <p>窗口内没有消息时返回 {@code saved=0, noiseOnly=false} 且不会调用 Dify；
     * 消息全被噪音过滤时返回 {@code saved=0, noiseOnly=true}，同样不调用 Dify。
     */
    DigestOutcome digestChat(Long chatId, String chatName, LocalDateTime start, LocalDateTime end);

    /**
     * 用户上次离开之后产生的整理结果，只含已勾选「纳入整理」的群。
     *
     * <p>排序：importance 降序，同权重按时间正序（先发生的事先读）。
     */
    List<DigestItemVO> listSince(LocalDateTime since);
}
