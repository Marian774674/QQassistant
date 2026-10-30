package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.client.DifyWorkflowClient;
import com.czx.qqmessageassistantjava.dto.MessageLogItem;
import com.czx.qqmessageassistantjava.entity.ChatDigestItem;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.ChatDigestItemMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 真库验证：单测里 mapper 全是 mock，落库这一层（唯一键、列宽、时钟）从来没被跑过。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DigestPersistenceTest {

    @Autowired
    private ChatDigestService chatDigestService;

    @Autowired
    private MessageLogService messageLogService;

    @Autowired
    private ChatDigestItemMapper digestItemMapper;

    @Autowired
    private MessageLogMapper messageLogMapper;

    @MockitoBean
    private DifyWorkflowClient difyWorkflowClient;

    private static final Long CHAT_ID = 9_900_001L;

    /** 一个群一小时通常不止一件事；同一小时的多条整理必须都能落库。 */
    @Test
    void digestChat_persistsEveryItemOfTheSameGroupHour() {
        LocalDateTime start = LocalDateTime.now().minusHours(1).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);
        List<Long> ids = seedMessages(start, end, 2);
        stubItems(
                item("十点开会，带材料", 5, ids.get(0)),
                item("有人问进度，没人回", 2, ids.get(1)));

        int saved = chatDigestService.digestChat(CHAT_ID, "多条目群", start, end);

        assertThat(saved).isEqualTo(2);
        assertThat(itemsOf(start)).extracting(ChatDigestItem::getSummary)
                .containsExactlyInAnyOrder("十点开会，带材料", "有人问进度，没人回");
    }

    /** 同一小时重跑是覆盖，不是追加。 */
    @Test
    void digestChat_rerunningTheSameHourReplacesInsteadOfAppending() {
        LocalDateTime start = LocalDateTime.now().minusHours(1).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);
        List<Long> ids = seedMessages(start, end, 2);
        stubItems(item("旧的一版", 3, ids.get(0)), item("旧的另一条", 3, ids.get(1)));
        chatDigestService.digestChat(CHAT_ID, "重跑群", start, end);

        stubItems(item("重跑后的唯一一条", 4, ids.get(1)));
        chatDigestService.digestChat(CHAT_ID, "重跑群", start, end);

        assertThat(itemsOf(start)).extracting(ChatDigestItem::getSummary)
                .containsExactly("重跑后的唯一一条");
    }

    /** 证据列必须装得下「同一话题合并后的一大串 id」。 */
    @Test
    void digestItem_acceptsAnEvidenceListFarLongerThan512Chars() {
        ChatDigestItem row = new ChatDigestItem();
        row.setChatId(CHAT_ID);
        row.setChatName("长证据群");
        LocalDateTime start = LocalDateTime.now().minusHours(2).withSecond(0).withNano(0);
        row.setPeriodStart(start);
        row.setPeriodEnd(start.plusHours(1));
        row.setSummary("合并了很多条");
        row.setImportance(3);
        row.setMentionsMe(false);
        row.setSourceMsgIds("100000001,".repeat(90) + "100000001");
        row.setCreatedAt(LocalDateTime.now());

        digestItemMapper.insert(row);

        assertThat(digestItemMapper.selectById(row.getId()).getSourceMsgIds()).hasSizeGreaterThan(900);
    }

    /** 库里记的时间要和 Java 的 now() 是同一个钟，否则每小时的窗口永远选不中消息。 */
    @Test
    void messageLog_createdAtUsesTheSameClockAsTheJvm() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(5);
        MessageLogItem item = new MessageLogItem();
        item.setSenderId(9_900_002L);
        item.setChatId(CHAT_ID);
        item.setDirection("IN");
        item.setMsgType("text");
        item.setContent("时钟对齐检查");
        item.setDedupeKey("clock-check-" + System.nanoTime());
        messageLogService.batchSave(List.of(item));

        MessageLog saved = messageLogMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MessageLog>()
                        .eq(MessageLog::getDedupeKey, item.getDedupeKey()));

        assertThat(saved).isNotNull();
        assertThat(Duration.between(before, saved.getCreatedAt()).abs())
                .isLessThan(Duration.ofMinutes(1));
    }

    private List<ChatDigestItem> itemsOf(LocalDateTime start) {
        return digestItemMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatDigestItem>()
                        .eq(ChatDigestItem::getChatId, CHAT_ID)
                        .eq(ChatDigestItem::getPeriodStart, start));
    }

    private List<Long> seedMessages(LocalDateTime start, LocalDateTime end, int count) {
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            MessageLog row = new MessageLog();
            row.setSenderId(9_900_010L + i);
            row.setSenderName("成员" + i);
            row.setChatId(CHAT_ID);
            row.setChatName("多条目群");
            row.setDirection("IN");
            row.setMsgType("text");
            row.setContent("第 " + i + " 条消息");
            row.setDedupeKey("seed-" + CHAT_ID + "-" + i + "-" + System.nanoTime());
            // 窗口比对的就是这一列，测试里显式给出，别依赖库默认值
            row.setCreatedAt(start.plusMinutes(10L * (i + 1)));
            messageLogMapper.insert(row);
            ids.add(row.getId());
        }
        return ids;
    }

    private Map<String, Object> item(String summary, int importance, Long evidenceId) {
        return Map.of(
                "summary", summary,
                "importance", importance,
                "mentions_me", false,
                "source_msg_ids", List.of(evidenceId));
    }

    @SafeVarargs
    private void stubItems(Map<String, Object>... items) {
        when(difyWorkflowClient.run(eq("digest"), anyMap()))
                .thenReturn(Map.of("items", List.of(items)));
    }
}
