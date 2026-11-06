package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.entity.MessageLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageNoiseFilterTest {

    @Mock
    AppSettingService appSettingService;

    private final Map<String, String> settings = new HashMap<>();
    private MessageNoiseFilter filter;

    @BeforeEach
    void setUp() {
        settings.clear();
        filter = new MessageNoiseFilter(appSettingService);
        // 模拟 app_setting：没存过的键就返回调用方给的默认值
        when(appSettingService.getOrDefault(anyString(), anyString()))
                .thenAnswer(invocation -> settings.getOrDefault(
                        invocation.getArgument(0), invocation.getArgument(1)));
    }

    @Test
    void dropsExactKeywords() {
        MessageNoiseFilter.FilterOutcome outcome =
                filter.filter(List.of(msg("666"), msg("笑死我了"), msg("收到")));

        assertThat(outcome.kept()).isEmpty();
        assertThat(outcome.dropped()).isEqualTo(3);
        assertThat(outcome.reasons()).containsEntry("keyword", 3);
    }

    @Test
    void dropsLongRepeatedCharacters() {
        MessageNoiseFilter.FilterOutcome outcome = filter.filter(List.of(msg("zzzz")));

        assertThat(outcome.kept()).isEmpty();
        assertThat(outcome.reasons()).containsEntry("repeat", 1);
    }

    @Test
    void dropsPunctuationAndEmojiOnly() {
        MessageNoiseFilter.FilterOutcome outcome =
                filter.filter(List.of(msg("！！！"), msg("😂😂"), msg("。。。")));

        assertThat(outcome.kept()).isEmpty();
        assertThat(outcome.reasons()).containsEntry("punctuation", 3);
    }

    @Test
    void dropsBlankText() {
        MessageNoiseFilter.FilterOutcome outcome = filter.filter(List.of(msg("   ")));

        assertThat(outcome.kept()).isEmpty();
        assertThat(outcome.reasons()).containsEntry("blank", 1);
    }

    @Test
    void keepsMentionsQuestionsTimesAndTasks() {
        List<MessageLog> rows = List.of(
                msg("@小明 明天几点开会？"),
                msg("10:30 集合"),
                msg("明天交作业"),
                msg("第 1 条消息"));

        assertThat(filter.filter(rows).kept()).hasSize(4);
    }

    @Test
    void keepsANoiseWordWhenItCarriesATimeSignal() {
        assertThat(filter.filter(List.of(msg("666 明天见"))).kept()).hasSize(1);
    }

    @Test
    void keepsFilesEvenWhenTheContentIsEmpty() {
        assertThat(filter.filter(List.of(msg("", "file"))).kept()).hasSize(1);
    }

    @Test
    void keepsMessagesThatMentionTheOwnerAlias() {
        settings.put("owner_aliases", "小明,明哥");

        assertThat(filter.filter(List.of(msg("明哥666"))).kept()).hasSize(1);
    }

    @Test
    void keepsEverythingWhenDisabled() {
        settings.put("noise_filter_enabled", "0");

        MessageNoiseFilter.FilterOutcome outcome =
                filter.filter(List.of(msg("666"), msg("哈哈哈"), msg("")));

        assertThat(outcome.kept()).hasSize(3);
        assertThat(outcome.dropped()).isZero();
    }

    @Test
    void acceptsFalseAsDisabledToo() {
        settings.put("noise_filter_enabled", "false");

        assertThat(filter.filter(List.of(msg("666"))).kept()).hasSize(1);
    }

    @Test
    void customKeywordsReplaceTheDefaultList() {
        settings.put("noise_filter_keywords", "广告");

        // 用非重复字符的默认关键词验证「默认列表确实被替换了」；
        // 666 仍会被默认的重复字符正则命中，不能拿它当反例
        assertThat(filter.filter(List.of(msg("广告"), msg("笑死我了"))).kept())
                .extracting(MessageLog::getContent)
                .containsExactly("笑死我了");
    }

    @Test
    void invalidRegexDoesNotBreakTheOtherRules() {
        settings.put("noise_filter_regex", "[");

        MessageNoiseFilter.FilterOutcome outcome =
                filter.filter(List.of(msg("666"), msg("第 1 条消息")));

        assertThat(outcome.kept()).extracting(MessageLog::getContent)
                .containsExactly("第 1 条消息");
    }

    private MessageLog msg(String content) {
        return msg(content, "text");
    }

    private MessageLog msg(String content, String msgType) {
        MessageLog row = new MessageLog();
        row.setId(1L);
        row.setChatId(9L);
        row.setSenderId(1000L);
        row.setSenderName("小明");
        row.setDirection("IN");
        row.setMsgType(msgType);
        row.setContent(content);
        return row;
    }
}
