package com.czx.qqmessageassistantjava.schedule;

import com.czx.qqmessageassistantjava.entity.ChatSetting;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import com.czx.qqmessageassistantjava.service.ChatDigestService;
import com.czx.qqmessageassistantjava.service.ChatSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 每小时整理一次：把上一个整点小时内、已勾选「纳入整理」的群归纳成几条要点。
 *
 * <p>默认关闭（{@code app_setting.digest_enabled = 0}）：Dify 没配好之前，谁也不希望每小时刷一堆错误日志。
 */
@Component
public class DigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(DigestScheduler.class);

    private final ChatSettingService chatSettingService;
    private final ChatDigestService chatDigestService;
    private final AppSettingService appSettingService;

    @Autowired
    public DigestScheduler(ChatSettingService chatSettingService,
                           ChatDigestService chatDigestService,
                           AppSettingService appSettingService) {
        this.chatSettingService = chatSettingService;
        this.chatDigestService = chatDigestService;
        this.appSettingService = appSettingService;
    }

    @Scheduled(cron = "0 5 * * * *")
    public void digestPreviousHour() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime[] window = windowOf(now);
        try {
            runOnce(window[0], window[1]);
        } catch (Exception e) {
            // 定时任务里抛异常会被调度器吞掉且不再触发后续，兜住
            log.error("整理上一小时消息失败", e);
        }
    }

    /** 只整理「上一整个小时」，避免半个小时的窗口每被扫两次 */
    static LocalDateTime[] windowOf(LocalDateTime now) {
        LocalDateTime end = now.withMinute(0).withSecond(0).withNano(0);
        return new LocalDateTime[]{end.minusHours(1), end};
    }

    /**
     * @return 写入的整理条数合计
     */
    public int runOnce(LocalDateTime periodStart, LocalDateTime periodEnd) {
        if (!"1".equals(appSettingService.getOrDefault("digest_enabled", "0"))) {
            return 0;
        }
        List<ChatSetting> groups = chatSettingService.listAll();
        int saved = 0;
        for (ChatSetting group : groups) {
            if (!"GROUP".equals(group.getKind()) || !Boolean.TRUE.equals(group.getDigestEnabled())) {
                continue;
            }
            try {
                saved += chatDigestService.digestChat(
                        group.getChatId(), group.getChatName(), periodStart, periodEnd);
            } catch (Exception e) {
                // 一个群失败不能连累其它群：Dify 超时、某个群消息太多都可能单独炸
                log.warn("整理群 {} 失败，跳过", group.getChatId(), e);
            }
        }
        return saved;
    }
}
