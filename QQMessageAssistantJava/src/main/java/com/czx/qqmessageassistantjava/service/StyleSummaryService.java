package com.czx.qqmessageassistantjava.service;

/**
 * 风格画像的定期总结（FR-6.3）。
 *
 * <p>按 {@code app_setting.summary_frequency}（DAILY_7D / DAILY_TODAY / WEEKLY）读取主人最近
 * 亲手发的消息，调 Dify Workflow 的 {@code task=style} 生成一段「怎么说话」的画像，写回
 * {@code style_profile}。**任何单条消息都不得触发画像更新**——只有这里（定时 / 手动）能改。
 *
 * <p>「到期」以 {@code style_profile.last_summarized_at} 为准，因此调度器可以在启动时、
 * 每 3 小时、每日各调一次 {@link #runIfDue()}：宕机错过定时点，重启后第一次检查就会补做，
 * 已做过则直接跳过。
 */
public interface StyleSummaryService {

    /** 是否到了该总结的时间点（按频率 + 上次总结时间判定）。 */
    boolean isDue();

    /** 到期才跑；返回是否真的写了画像。 */
    boolean runIfDue();

    /**
     * 立刻跑一次（不看是否到期，供手动触发 / 兜底使用）。
     * 失败、没有消息、模型没产出都返回 {@code false}，且**不改动旧画像与 lastSummarizedAt**。
     */
    boolean summarize();
}
