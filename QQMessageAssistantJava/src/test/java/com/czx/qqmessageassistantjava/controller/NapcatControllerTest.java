package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.config.AgentTokenFilter;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 补拉接口的契约：参数写错是「业务拒绝」（HTTP 200 + code!=0），
 * 不是 4xx——插件的 HTTP 客户端只在非 2xx 时抛错，混用会让「参数写错了」
 * 被当成「后端没起来」，补拉于是静默停摆。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NapcatControllerTest {

    private static final String TOKEN = "test-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MessageLogMapper messageLogMapper;

    @Test
    void missingTokenIsRejectedBeforeTheController() throws Exception {
        mockMvc.perform(get("/api/agent/napcat/targets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void targetsListChatsThatHaveRecentMessages() throws Exception {
        // 7 天窗口内的一条群消息 → 这个群应该出现在补拉目标里
        insertLog(660001L, "三班群");
        // 8 天前的老群：补它没有收益，只会多打一次 QQ 接口
        insertOldLog(660002L, "老群");

        mockMvc.perform(get("/api/agent/napcat/targets")
                        .header(AgentTokenFilter.HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                // 过滤表达式取到的是数组，所以用 contains 而不是直接比字符串
                .andExpect(jsonPath("$.data[?(@.chatId == 660001)].chatType", contains("GROUP")))
                .andExpect(jsonPath("$.data[?(@.chatId == 660001)].lastMessageSeq", contains(0)))
                .andExpect(jsonPath("$.data[?(@.chatId == 660002)]").isEmpty());
    }

    @Test
    void cursorAdvancesAndShowsUpInTheNextTargetsCall() throws Exception {
        insertLog(660011L, "四班群");

        mockMvc.perform(post("/api/agent/napcat/cursor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AgentTokenFilter.HEADER, TOKEN)
                        .content("""
                                {"chatType":"GROUP","chatId":660011,"lastMessageSeq":987654}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(987654));

        mockMvc.perform(get("/api/agent/napcat/targets")
                        .header(AgentTokenFilter.HEADER, TOKEN))
                .andExpect(jsonPath("$.data[?(@.chatId == 660011)].lastMessageSeq",
                        contains(987654)));
    }

    @Test
    void cursorNeverGoesBackwards() throws Exception {
        insertLog(660021L, "五班群");
        String body = """
                {"chatType":"GROUP","chatId":660021,"lastMessageSeq":%d}
                """;

        mockMvc.perform(post("/api/agent/napcat/cursor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AgentTokenFilter.HEADER, TOKEN)
                        .content(body.formatted(500)))
                .andExpect(jsonPath("$.data").value(500));

        // 重放一个更小的值（比如上一轮失败的补拉迟到了）：游标必须留在 500
        mockMvc.perform(post("/api/agent/napcat/cursor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AgentTokenFilter.HEADER, TOKEN)
                        .content(body.formatted(100)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(500));
    }

    @Test
    void badChatTypeIsABusinessRejectionNotA4xx() throws Exception {
        mockMvc.perform(post("/api/agent/napcat/cursor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AgentTokenFilter.HEADER, TOKEN)
                        .content("""
                                {"chatType":"FRIEND","chatId":1,"lastMessageSeq":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("chatType")));
    }

    private void insertLog(Long chatId, String chatName) {
        insertLog(chatId, chatName, LocalDateTime.now().minusHours(1));
    }

    private void insertOldLog(Long chatId, String chatName) {
        insertLog(chatId, chatName, LocalDateTime.now().minusDays(8));
    }

    private void insertLog(Long chatId, String chatName, LocalDateTime createdAt) {
        MessageLog row = new MessageLog();
        row.setSenderId(880000L + chatId);
        row.setSenderName("小明");
        row.setChatId(chatId);
        row.setChatName(chatName);
        row.setDirection("IN");
        row.setMsgType("text");
        row.setContent("明天下午两点开班会");
        row.setDedupeKey("test:" + UUID.randomUUID());
        row.setCreatedAt(createdAt);
        messageLogMapper.insert(row);
    }
}
