package com.czx.qqmessageassistantjava.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自动回复的决策快照：插件在调 Dify 之前取一次，拿齐「能不能说、还能说几条、人设、刚才在聊什么」。
 *
 * <p>拆成三次请求会让三个值之间互相不一致（比如刚发完一条、限流窗口已经变了），
 * 所以这里一次给全；{@code enabled=false} 时其余字段一样要有值——
 * 插件仍然要用 {@code recentMessages} 让 Dify 看懂上下文。
 */
@Data
public class ReplyContextVO {

    /** 该会话是否允许替主人说日常话；没建开关行 = false */
    private Boolean enabled;
    /** 主人写的额外要求；空串 = 没写 */
    private String replyPrompt;
    /** 每小时最多几条 CHAT 回复 */
    private Integer rateLimit;
    /** 最近一小时已经说出去的 CHAT 条数 */
    private Long outLastHour;
    /** 本会话最近 N 条，按时间正序 */
    private List<RecentMessage> recentMessages;

    @Data
    public static class RecentMessage {
        /** 发送者昵称；空 = 机器人自己说的，或插件没拿到 */
        private String senderName;
        /** IN | OUT */
        private String direction;
        private String content;
        private LocalDateTime createdAt;
    }
}
