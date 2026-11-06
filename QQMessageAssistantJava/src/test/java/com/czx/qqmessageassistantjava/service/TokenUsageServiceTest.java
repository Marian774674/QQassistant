package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.constant.TuningDefaults;
import com.czx.qqmessageassistantjava.dto.TokenUsageRecordRequest;
import com.czx.qqmessageassistantjava.entity.TokenUsage;
import com.czx.qqmessageassistantjava.mapper.TokenUsageMapper;
import com.czx.qqmessageassistantjava.vo.TokenUsageChatVO;
import com.czx.qqmessageassistantjava.vo.TokenUsageSummaryVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账本服务不依赖 Spring 上下文：验证「只追加、参数收口、窗口收敛」，
 * 顺便把 MySQL 别名大小写 / SUM 返回 BigDecimal 这两类脏数据挡在前端之前。
 */
@ExtendWith(MockitoExtension.class)
class TokenUsageServiceTest {

    @Mock
    private TokenUsageMapper mapper;

    private TokenUsageService service() {
        return new TokenUsageService(mapper);
    }

    private static TokenUsageRecordRequest request(String source, String task) {
        TokenUsageRecordRequest r = new TokenUsageRecordRequest();
        r.setSource(source);
        r.setTask(task);
        r.setPromptTokens(12);
        r.setCompletionTokens(3);
        return r;
    }

    @Test
    void recordDerivesTotalAndNormalizesSource() {
        TokenUsageRecordRequest r = request(" PLUGIN ", "chat");
        r.setModel("  qwen-plus  ");
        r.setChatId(12345L);

        TokenUsage row = service().record(r);

        assertEquals("plugin", row.getSource());
        assertEquals("chat", row.getTask());
        assertEquals("qwen-plus", row.getModel());
        assertEquals(12, row.getPromptTokens());
        assertEquals(3, row.getCompletionTokens());
        assertEquals(15, row.getTotalTokens());
        assertEquals(12345L, row.getChatId());
        verify(mapper).insert(any(TokenUsage.class));
    }

    @Test
    void recordDefaultsMissingSourceAndClampsBadNumbers() {
        TokenUsageRecordRequest r = new TokenUsageRecordRequest();
        r.setTask("digest");
        r.setPromptTokens(-8);
        r.setCompletionTokens(999_999_999);

        TokenUsage row = service().record(r);

        assertEquals("spring", row.getSource());
        assertEquals(0, row.getPromptTokens());
        assertEquals(TuningDefaults.TOKEN_USAGE_MAX_TOKENS, row.getCompletionTokens());
        assertEquals(TuningDefaults.TOKEN_USAGE_MAX_TOKENS, row.getTotalTokens());
        assertNull(row.getModel());
    }

    @Test
    void recordRejectsUnknownSourceAndBlankTask() {
        assertThrows(BusinessException.class, () -> service().record(request("hacker", "chat")));
        assertThrows(BusinessException.class, () -> service().record(request("plugin", "  ")));
        assertThrows(BusinessException.class, () -> service().record(null));
    }

    @Test
    void summaryNormalizesDriverSpecificKeysAndNumbers() {
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("CALLS", 3L);
        totals.put("PROMPTTOKENS", new BigDecimal("30"));
        totals.put("COMPLETIONTOKENS", 6);
        totals.put("TOTALTOKENS", new BigDecimal("36"));
        when(mapper.selectTotals(any())).thenReturn(totals);
        when(mapper.selectByTask(any())).thenReturn(List.of(Map.of("TASK", "chat", "CALLS", 2L,
                "TOKENS", new BigDecimal("24"))));
        when(mapper.selectBySource(any())).thenReturn(null);
        when(mapper.selectByDay(any())).thenReturn(List.of());

        TokenUsageSummaryVO vo = service().summary(7);

        assertEquals(3, vo.getCalls());
        assertEquals(30, vo.getPromptTokens());
        assertEquals(6, vo.getCompletionTokens());
        assertEquals(36, vo.getTotalTokens());
        assertEquals(1, vo.getByTask().size());
        assertEquals("chat", vo.getByTask().get(0).get("task"));
        assertEquals(2L, vo.getByTask().get(0).get("calls"));
        assertEquals(new BigDecimal("24"), vo.getByTask().get(0).get("tokens"));
        assertEquals(List.of(), vo.getBySource());
    }

    @Test
    void summaryClampsWindowAndFallsBackToDefaultDays() {
        when(mapper.selectTotals(any())).thenReturn(null);
        when(mapper.selectByTask(any())).thenReturn(List.of());
        when(mapper.selectBySource(any())).thenReturn(List.of());
        when(mapper.selectByDay(any())).thenReturn(List.of());

        assertEquals(TuningDefaults.TOKEN_USAGE_MAX_DAYS, service().summary(9999).getDays());
        assertEquals(TuningDefaults.TOKEN_USAGE_DEFAULT_DAYS, service().summary(0).getDays());
        assertEquals(TuningDefaults.TOKEN_USAGE_DEFAULT_DAYS, service().summary(-3).getDays());

        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper, org.mockito.Mockito.times(3)).selectTotals(since.capture());
        long daysBetween = java.time.Duration
                .between(since.getAllValues().get(0), LocalDateTime.now()).toDays();
        assertEquals(TuningDefaults.TOKEN_USAGE_MAX_DAYS, daysBetween);
    }

    /** 7 个群 + 1 组「无 chatId」：Top6 + 其它 1 个 + 未归属，按占比降序 */
    @Test
    void summaryBuildsChatPieWithUnassignedSliceAndMergedTail() {
        stubEmptySummary();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            String raw = i == 1 ? "一班，二班，三班" : (i == 2 ? null : "  ");
            rows.add(chatRow((long) i, (8 - i) * 100L, 1, raw));
        }
        rows.add(chatRow(null, 1500, 9, null));
        when(mapper.selectByChat(any())).thenReturn(rows);

        List<TokenUsageChatVO> slices = service().summary(7).getByChat();

        assertEquals(8, slices.size());
        // 未归属最大，排第一；它是一整块，不参与 Top6 竞争
        assertEquals("unassigned", slices.get(0).getKind());
        assertEquals("未归属会话", slices.get(0).getLabel());
        assertNull(slices.get(0).getChatId());
        assertEquals(1500, slices.get(0).getTokens());
        assertEquals(9, slices.get(0).getCalls());
        // 别名里的一串名字只展示第一个
        assertEquals("一班", slices.get(1).getLabel());
        assertEquals(1L, slices.get(1).getChatId().longValue());
        // 没有命名行时回落「群 + 群号」，空白名同样算没有
        assertEquals("群 2", slices.get(2).getLabel());
        assertEquals("群 3", slices.get(3).getLabel());
        // 最后一块是 Top6 之外合并出来的
        TokenUsageChatVO other = slices.get(slices.size() - 1);
        assertEquals("other", other.getKind());
        assertEquals("其它 1 个会话", other.getLabel());
        assertNull(other.getChatId());
        assertEquals(100, other.getTokens());
        // 各扇之和等于账本总量，饼图占比才对得上
        assertEquals(4300, slices.stream().mapToLong(TokenUsageChatVO::getTokens).sum());
    }

    /** 驱动把别名写成大写、chatId 给成字符串也要能收口；0 token 的行不该占扇区 */
    @Test
    void summaryChatPieNormalizesDriverKeysAndSkipsZeroTokenRows() {
        stubEmptySummary();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("CHATID", "987654"); // 字符串形式的群号
        row.put("CALLS", new BigDecimal("2"));
        row.put("TOKENS", new BigDecimal("36"));
        row.put("RAWNAME", "研究生群,大群");
        when(mapper.selectByChat(any())).thenReturn(List.of(
                row, chatRow(22L, 0, 3, "零消耗群")));

        List<TokenUsageChatVO> slices = service().summary(7).getByChat();

        assertEquals(1, slices.size());
        assertEquals("chat", slices.get(0).getKind());
        assertEquals(987654L, slices.get(0).getChatId().longValue());
        assertEquals("研究生群", slices.get(0).getLabel());
        assertEquals(36, slices.get(0).getTokens());
        assertEquals(2, slices.get(0).getCalls());
    }

    @Test
    void summaryChatPieIsEmptyWhenThereAreNoRows() {
        stubEmptySummary();
        when(mapper.selectByChat(any())).thenReturn(null);

        assertEquals(List.of(), service().summary(7).getByChat());
    }

    private void stubEmptySummary() {
        when(mapper.selectTotals(any())).thenReturn(null);
        when(mapper.selectByTask(any())).thenReturn(List.of());
        when(mapper.selectBySource(any())).thenReturn(List.of());
        when(mapper.selectByDay(any())).thenReturn(List.of());
    }

    private static Map<String, Object> chatRow(Long chatId, long tokens, long calls, String rawName) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chatId", chatId);
        row.put("calls", calls);
        row.put("tokens", tokens);
        row.put("rawName", rawName);
        return row;
    }
}
