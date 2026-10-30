package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czx.qqmessageassistantjava.client.DifyWorkflowClient;
import com.czx.qqmessageassistantjava.entity.MemoryNote;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.MemoryNoteMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 夜间归纳：模型只负责「谁 · 什么属性 · 什么值」，Java 负责「证据必须在本批里、不许重复写、游标只前进」。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoryJobServiceTest {

    @Autowired
    private MemoryJobService memoryJobService;

    @Autowired
    private MessageLogMapper messageLogMapper;

    @Autowired
    private MemoryNoteMapper memoryNoteMapper;

    @Autowired
    private ContactService contactService;

    @Autowired
    private AppSettingService appSettingService;

    @MockitoBean
    private DifyWorkflowClient difyWorkflowClient;

    private static final Long SENDER = 9_930_001L;
    private static final Long CHAT = 9_930_100L;

    @Test
    void runOnce_writesNotesAndAdvancesTheCursor() {
        List<Long> ids = seedMessages(2);
        armCursor(ids.get(0));
        String alias = "测试小明-" + System.nanoTime();
        stubExtract(
                contacts("[{\"sender_id\":\"" + SENDER + "\",\"alias\":\"" + alias + "\",\"relation\":\"同学\"}]"),
                notes("[{\"subject_sender_id\":\"" + SENDER + "\",\"predicate\":\"生日\","
                        + "\"value\":\"11月11日\",\"source_msg_ids\":[" + ids.get(0) + "]}]"));

        assertThat(memoryJobService.runOnce()).isEqualTo(1);

        assertThat(notesOf(SENDER)).extracting(MemoryNote::getValue).contains("11月11日");
        assertThat(contactService.findByAlias(alias)).extracting(c -> c.getSenderId()).contains(SENDER);
        assertThat(appSettingService.getOrDefault("memory_job_cursor", ""))
                .isEqualTo(String.valueOf(ids.get(ids.size() - 1)));
    }

    @Test
    void runOnce_dropsNoteWhoseEvidenceIsOutsideTheBatch() {
        List<Long> ids = seedMessages(1);
        armCursor(ids.get(0));
        stubExtract("[]", notes("[{\"subject_sender_id\":\"" + SENDER + "\",\"predicate\":\"生日\","
                + "\"value\":\"不该写进来\",\"source_msg_ids\":[99999999]}]"));

        assertThat(memoryJobService.runOnce()).isZero();
        assertThat(notesOf(SENDER)).extracting(MemoryNote::getValue).doesNotContain("不该写进来");
    }

    @Test
    void runOnce_keepsTheDateTheModelExtracted() {
        List<Long> ids = seedMessages(1);
        armCursor(ids.get(0));
        stubExtract("[]", notes("[{\"subject_sender_id\":\"" + SENDER + "\",\"predicate\":\"时间安排\","
                + "\"value\":\"下周三考高数\",\"happened_at\":\"2026-11-04\","
                + "\"source_msg_ids\":[" + ids.get(0) + "]}]"));

        assertThat(memoryJobService.runOnce()).isEqualTo(1);

        assertThat(notesOf(SENDER)).singleElement()
                .extracting(MemoryNote::getHappenedAt)
                .isEqualTo(LocalDateTime.of(2026, 11, 4, 0, 0));
    }

    @Test
    void runOnce_skipsANoteWhoseValueAlreadyExists() {
        List<Long> ids = seedMessages(1);
        armCursor(ids.get(0));
        String value = "重复值-" + System.nanoTime();
        String payload = notes("[{\"subject_sender_id\":\"" + SENDER + "\",\"predicate\":\"生日\","
                + "\"value\":\"" + value + "\",\"source_msg_ids\":[" + ids.get(0) + "]}]");

        stubExtract("[]", payload);
        assertThat(memoryJobService.runOnce()).isEqualTo(1);

        // 模拟这一批被重跑（夜里失败后游标回拨）：同一条事实不能再插一遍
        armCursor(ids.get(0));
        stubExtract("[]", payload);
        assertThat(memoryJobService.runOnce()).isZero();
        assertThat(notesOf(SENDER)).extracting(MemoryNote::getValue).containsOnlyOnce(value);
    }

    @Test
    void rebuild_resetsTheCursorAndDrainsTheWholeHistory() {
        List<Long> ids = seedMessages(2);
        appSettingService.putAll(Map.of(
                "memory_job_enabled", "1",
                // 平时停在末尾，重建必须回到 0 才能把历史补上
                "memory_job_cursor", String.valueOf(ids.get(ids.size() - 1))));
        String value = "重建补上的值-" + System.nanoTime();
        stubExtract("[]", notes("[{\"subject_sender_id\":\"" + SENDER + "\",\"predicate\":\"专业\","
                + "\"value\":\"" + value + "\",\"source_msg_ids\":[" + ids.get(0) + "]}]"));

        // 游标本来停在末尾：能写出这条事实，就证明 rebuild 确实把它拨回 0 重新扫过
        assertThat(memoryJobService.rebuild()).isEqualTo(1);
        assertThat(notesOf(SENDER)).extracting(MemoryNote::getValue).contains(value);
    }

    @Test
    void runOnce_isNoOpWhenTheSwitchIsOff() {
        List<Long> ids = seedMessages(1);
        armCursor(ids.get(0));
        appSettingService.putAll(Map.of("memory_job_enabled", "0"));

        assertThat(memoryJobService.runOnce()).isZero();
        verifyNoInteractions(difyWorkflowClient);
    }

    @Test
    void runOnce_returnsZeroOnTheSecondPassWithoutCallingDifyAgain() {
        List<Long> ids = seedMessages(1);
        armCursor(ids.get(0));
        stubExtract("[]", "[]");

        assertThat(memoryJobService.runOnce()).isZero();
        verify(difyWorkflowClient).run(eq("extract"), anyMap());
        assertThat(memoryJobService.runOnce()).isZero();
    }

    private List<Long> seedMessages(int count) {
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            MessageLog row = new MessageLog();
            row.setSenderId(SENDER);
            row.setSenderName("小明");
            row.setChatId(CHAT);
            row.setChatName("三班群");
            row.setDirection("IN");
            row.setMsgType("text");
            row.setContent("我生日是 11 月 11 日");
            row.setCreatedAt(LocalDateTime.now());
            row.setDedupeKey("memjob-" + System.nanoTime() + "-" + i);
            messageLogMapper.insert(row);
            ids.add(row.getId());
        }
        return ids;
    }

    /** 游标停在第一条之前：本批恰好只含这几条测试消息，不受库里老数据影响 */
    private void armCursor(Long firstId) {
        appSettingService.putAll(Map.of(
                "memory_job_enabled", "1",
                "memory_job_cursor", String.valueOf(firstId - 1)));
    }

    private List<MemoryNote> notesOf(Long subject) {
        return memoryNoteMapper.selectList(new LambdaQueryWrapper<MemoryNote>()
                .eq(MemoryNote::getSubjectSenderId, subject));
    }

    private void stubExtract(String contacts, String notes) {
        when(difyWorkflowClient.run(eq("extract"), anyMap()))
                .thenReturn(Map.of("contacts", contacts, "notes", notes));
    }

    private static String contacts(String json) {
        return json;
    }

    private static String notes(String json) {
        return json;
    }
}
