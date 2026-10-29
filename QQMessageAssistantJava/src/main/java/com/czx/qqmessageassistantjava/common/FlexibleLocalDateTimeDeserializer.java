package com.czx.qqmessageassistantjava.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 宽松的 LocalDateTime 反序列化。
 *
 * 背景：dueTime 主要来自 Dify LLM，模型经常输出 "2026-10-29 19:00" 这类非 ISO 文本。
 * Jackson 默认只认 "2026-10-29T19:00:00"，一个空格就会让整条备忘 500 且丢失信息。
 * 这里按常见写法逐个尝试，仍然失败才抛错（错误信息里带原值，便于排查 Prompt）。
 */
public class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

    private static final DateTimeFormatter[] DATE_TIME_FORMATS = {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
    };

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
    };

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String text = p.getValueAsString();
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();

        for (DateTimeFormatter format : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // 换下一个格式
            }
        }
        // 带时区/偏移（2026-10-29T19:00:00+08:00 或 ...Z）→ 统一成本地时间
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 继续
        }
        // 只有日期（2026-10-29）→ 当天零点
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // 继续
            }
        }
        throw new IOException("无法解析时间：" + value + "（需要形如 2026-10-29T19:00:00）");
    }
}
