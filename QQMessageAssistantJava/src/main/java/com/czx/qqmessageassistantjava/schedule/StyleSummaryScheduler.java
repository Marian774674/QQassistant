package com.czx.qqmessageassistantjava.schedule;

import com.czx.qqmessageassistantjava.service.StyleSummaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 风格画像定期总结的调度（FR-6.3）。
 *
 * <p>三条触发线，共用同一个「到期判定」({@code lastSummarizedAt} + {@code summary_frequency})：
 * <ol>
 *   <li>每天 00:20 正常跑一次；</li>
 *   <li>每 3 小时兜底：定时点那一刻进程不在、或 Dify 临时失败时，后续 tick 会补上；</li>
 *   <li><b>启动补做</b>：宕机错过了 00:20，重启后 {@link ApplicationReadyEvent} 立刻检查一次，
 *       过期就当场补做——这正是「系统重新运作时也能完成总结」的落点。</li>
 * </ol>
 *
 * <p>因为 {@link StyleSummaryService#runIfDue()} 幂等（做完就把 {@code lastSummarizedAt} 推到当前），
 * 多条线重复触发也只会真正执行一次。
 */
@Component
public class StyleSummaryScheduler {

    private static final Logger log = LoggerFactory.getLogger(StyleSummaryScheduler.class);

    private final StyleSummaryService styleSummaryService;

    public StyleSummaryScheduler(StyleSummaryService styleSummaryService) {
        this.styleSummaryService = styleSummaryService;
    }

    @Scheduled(cron = "0 20 0 * * *")
    public void nightly() {
        runQuietly("每日");
    }

    /** 兜底：每 3 小时检查一次是否到期；没到期就是一次廉价的判定。 */
    @Scheduled(cron = "0 20 */3 * * *")
    public void sweep() {
        runQuietly("兜底");
    }

    /**
     * 启动补做。放在独立守护线程里跑：Dify 可能慢，不能拖住应用启动。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        Thread worker = new Thread(() -> runQuietly("启动补做"), "style-summary-startup");
        worker.setDaemon(true);
        worker.start();
    }

    private void runQuietly(String reason) {
        try {
            if (styleSummaryService.runIfDue()) {
                log.info("风格画像总结（{}）已执行", reason);
            }
        } catch (Exception e) {
            // 定时任务抛异常会被调度器吞掉且不再触发后续，兜住
            log.error("风格画像总结（{}）失败", reason, e);
        }
    }
}
