package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.vo.ReplyContextVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动回复的决策快照：插件调一次就要拿齐「能不能说、还能说几条、主人怎么写的人设、刚才在聊什么」。
 *
 * <p>这些行为只有真库验得出来：{@code reply_kind} 的过滤、时间窗、索引顺序、以及
 * 「没建开关行 = 关」这条默认语义。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReplyContextTest {

    @Autowired
    private ChatSettingService chatSettingService;

    @Autowired
    private AppSettingService appSettingService;

    @Autowired
    private MessageLogMapper messageLogMapper;

    private static final Long CHAT = 9_960_000L;

    @Test
    void replyContext_reportsOffWhenNoSettingRowExists() {
        seed(CHAT, "IN", null, 5, "在吗");

        ReplyContextVO ctx = chatSettingService.replyContext(CHAT, "GROUP", 15);

        // 没建开关行就是关——默认替主人说话是最不能默默发生的事
        assertThat(ctx.getEnabled()).isFalse();
        assertThat(ctx.getRecentMessages()).hasSize(1);
    }

    @Test
    void replyContext_readsTheSwitchAndThePrompt() {
        chatSettingService.upsert(CHAT + 1, "GROUP", "三班群", null, true);
        appSettingService.putAll(Map.of("reply_prompt", "我是张三，说话随意点"));

        ReplyContextVO ctx = chatSettingService.replyContext(CHAT + 1, "GROUP", 15);

        assertThat(ctx.getEnabled()).isTrue();
        assertThat(ctx.getReplyPrompt()).isEqualTo("我是张三，说话随意点");
    }

    @Test
    void replyContext_countsOnlyChatRepliesInTheLastHour() {
        seed(CHAT + 2, "OUT", "CHAT", 10, "自动回复 1");
        seed(CHAT + 2, "OUT", "CHAT", 20, "自动回复 2");
        seed(CHAT + 2, "OUT", "CHAT", 59, "自动回复 3");
        // 下面三条都不该计入：任务确认语、太早的、别人说的
        seed(CHAT + 2, "OUT", "TASK", 10, "改好了：班会");
        seed(CHAT + 2, "OUT", "CHAT", 120, "两小时前");
        seed(CHAT + 2, "IN", null, 10, "别人说的");

        ReplyContextVO ctx = chatSettingService.replyContext(CHAT + 2, "GROUP", 15);

        assertThat(ctx.getOutLastHour()).isEqualTo(3);
    }

    @Test
    void replyContext_returnsRecentMessagesInChronologicalOrder() {
        seed(CHAT + 3, "IN", null, 30, "第一条");
        seed(CHAT + 3, "OUT", "CHAT", 20, "第二条");
        seed(CHAT + 3, "IN", null, 10, "第三条");

        ReplyContextVO ctx = chatSettingService.replyContext(CHAT + 3, "GROUP", 2);

        // limit 取的是「最近的 N 条」，但交给 Dify 时必须按正序读起来像一段对话
        assertThat(ctx.getRecentMessages()).extracting(ReplyContextVO.RecentMessage::getContent)
                .containsExactly("第二条", "第三条");
    }

    @Test
    void replyContext_globalSwitchOffSilencesEveryChat() {
        // 总开关是一键闭嘴：单聊/群一个个去关太慢，出事时得能立刻全停
        chatSettingService.upsert(CHAT + 6, "GROUP", "三班群", null, true);
        chatSettingService.upsert(CHAT + 7, "PRIVATE", "阿黄", null, true);
        appSettingService.putAll(Map.of("reply_enabled", "false"));

        assertThat(chatSettingService.replyContext(CHAT + 6, "GROUP", 15).getEnabled()).isFalse();
        assertThat(chatSettingService.replyContext(CHAT + 7, "PRIVATE", 15).getEnabled()).isFalse();
    }

    @Test
    void replyContext_globalSwitchDefaultsToOn() {
        // 没配过就是开：升级不能把别人已经打开的会话顺手关掉
        chatSettingService.upsert(CHAT + 8, "GROUP", "三班群", null, true);

        assertThat(chatSettingService.replyContext(CHAT + 8, "GROUP", 15).getEnabled()).isTrue();
    }

    @Test
    void replyContext_ratesLimitDefaultsToTwenty() {
        ReplyContextVO ctx = chatSettingService.replyContext(CHAT + 4, "GROUP", 15);

        assertThat(ctx.getRateLimit()).isEqualTo(20);
    }

    @Test
    void replyContext_rejectsAnUnknownKind() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> chatSettingService.replyContext(CHAT + 5, "FRIENDS", 15))
                .isInstanceOf(com.czx.qqmessageassistantjava.common.BusinessException.class);
    }

    @Test
    void replyContext_privateChatCountsByPeerBecauseChatIdIsNull() {
        // 私聊的 message_log.chat_id 一律是 NULL（没有群号），会话键是对方 QQ
        seedPrivate(PEER, "OUT", "CHAT", 10, "私聊回复 1");
        seedPrivate(PEER, "OUT", "CHAT", 30, "私聊回复 2");
        seedPrivate(PEER + 1, "OUT", "CHAT", 10, "别人的私聊");
        // 群号恰好等于对方 QQ：这是另一个会话，不能算进私聊的额度
        seed(PEER, "OUT", "CHAT", 10, "那个群里的回复");

        ReplyContextVO ctx = chatSettingService.replyContext(PEER, "PRIVATE", 15);

        assertThat(ctx.getOutLastHour()).isEqualTo(2);
    }

    @Test
    void replyContext_privateChatReturnsOnlyThatPeerMessages() {
        seedPrivate(PEER, "IN", null, 30, "在吗");
        seedPrivate(PEER, "OUT", "CHAT", 20, "在的");
        seedPrivate(PEER + 1, "IN", null, 10, "别人的消息");
        seed(PEER, "IN", null, 5, "群里的消息");

        ReplyContextVO ctx = chatSettingService.replyContext(PEER, "PRIVATE", 15);

        assertThat(ctx.getRecentMessages()).extracting(ReplyContextVO.RecentMessage::getContent)
                .containsExactly("在吗", "在的");
    }

    @Test
    void replyContext_privateChatIsOffUntilItsOwnRowExists() {
        seedPrivate(PEER, "IN", null, 5, "在吗");
        // 群开着不等于私聊开着：两个会话，两份开关
        chatSettingService.upsert(PEER, "GROUP", "三班群", null, true);

        ReplyContextVO ctx = chatSettingService.replyContext(PEER, "PRIVATE", 15);

        assertThat(ctx.getEnabled()).isFalse();
    }

    private static final Long PEER = 9_960_777L;

    /** 插一条私聊日志：没有群号，只有对方 QQ */
    private void seedPrivate(Long senderId, String direction, String replyKind,
                             int minutesAgo, String content) {
        MessageLog row = new MessageLog();
        row.setSenderId(senderId);
        row.setSenderName("阿黄");
        row.setChatId(null);
        row.setDirection(direction);
        row.setReplyKind(replyKind);
        row.setMsgType("text");
        row.setContent(content);
        row.setCreatedAt(LocalDateTime.now().minusMinutes(minutesAgo));
        row.setDedupeKey("reply-ctx-p-" + System.nanoTime() + "-" + minutesAgo);
        messageLogMapper.insert(row);
    }

    /** 插一条日志；minutesAgo 越大越早 */
    private void seed(Long chatId, String direction, String replyKind, int minutesAgo, String content) {
        MessageLog row = new MessageLog();
        row.setSenderId(9_960_001L);
        row.setSenderName("阿黄");
        row.setChatId(chatId);
        row.setDirection(direction);
        row.setReplyKind(replyKind);
        row.setMsgType("text");
        row.setContent(content);
        row.setCreatedAt(LocalDateTime.now().minusMinutes(minutesAgo));
        row.setDedupeKey("reply-ctx-" + System.nanoTime() + "-" + minutesAgo);
        messageLogMapper.insert(row);
    }
}
