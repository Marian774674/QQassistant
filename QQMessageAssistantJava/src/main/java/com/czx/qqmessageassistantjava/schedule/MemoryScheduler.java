package com.czx.qqmessageassistantjava.schedule;

import com.czx.qqmessageassistantjava.constant.AppSettingKeys;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import com.czx.qqmessageassistantjava.service.MemoryJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天 0:10 把前一天的消息过一遍，抽成 contact / memory_note。
 *
 * <p>默认关闭（{@code app_setting.memory_job_enabled = 0}）：这是唯一会持续烧 token 的夜间任务，
 * 开关必须显式打开。
 */
@Component
public class MemoryScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemoryScheduler.class);

    private final MemoryJobService memoryJobService;
    private final AppSettingService appSettingService;

    public MemoryScheduler(MemoryJobService memoryJobService, AppSettingService appSettingService) {
        this.memoryJobService = memoryJobService;
        this.appSettingService = appSettingService;
    }

    @Scheduled(cron = "0 10 0 * * *")
    public void nightly() {
        try {
            runOnce();
        } catch (Exception e) {
            // 定时任务里抛异常会被调度器吞掉且不再触发后续，兜住
            log.error("夜间归纳失败", e);
        }
    }

    /** @return 写入的事实条数 */
    public int runOnce() {
        if (!"1".equals(appSettingService.getOrDefault(AppSettingKeys.MEMORY_JOB_ENABLED, "0").trim())) {
            return 0;
        }
        return memoryJobService.runOnce();
    }

    /**
     * 启动补做：宕机错过了 0:10 的夜间归纳，重启后把游标之后的积压消息补算掉。
     *
     * <p>游标是持久的，所以这里不会重跑已处理的批次，只是把落下的赶上来；放在守护线程里，
     * 不拖住应用启动。开关没开就直接返回，不产生任何 Dify 调用。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        if (!"1".equals(appSettingService.getOrDefault(AppSettingKeys.MEMORY_JOB_ENABLED, "0").trim())) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                int written = memoryJobService.catchUp();
                if (written > 0) {
                    log.info("夜间归纳启动补做：写入 {} 条事实", written);
                }
            } catch (Exception e) {
                log.error("夜间归纳启动补做失败", e);
            }
        }, "memory-job-startup");
        worker.setDaemon(true);
        worker.start();
    }
}
