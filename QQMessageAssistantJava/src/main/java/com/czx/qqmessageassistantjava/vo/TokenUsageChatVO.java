package com.czx.qqmessageassistantjava.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 「花在哪个会话」饼图的一扇。
 *
 * <p>{@code kind} 决定这一扇是不是一个真实会话：
 * {@code chat} = 某个群（{@code chatId} 有值）；
 * {@code unassigned} = 账本里 {@code chat_id IS NULL} 的调用（问一句 / 画像抽取 /
 * 夜间归纳 / 私聊自动回复）；{@code other} = Top N 之外合并的小会话。
 * 后两者 {@code chatId} 恒为 null，前端用中性色区分，避免和真实群聊混在一起。
 *
 * <p>只给 {@code tokens} / {@code calls}，占比交给前端按本数组之和算：
 * 后端不引入浮点，也方便以后改 Top N 而不用调接口。
 */
@Data
@Schema(description = "按会话聚合的 token 用量（饼图一扇）")
public class TokenUsageChatVO {

    /** 群号；未归属会话与「其它会话」为 null */
    private Long chatId;

    /** 展示名：用户起的别名 > chat_setting.chat_name > 「群 + 群号」 */
    private String label;

    /** chat | unassigned | other */
    private String kind;

    private long calls;

    private long tokens;
}
