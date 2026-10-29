package com.czx.qqmessageassistantjava.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** Dify HTTP 节点拿到的写操作结果；人话确认语放在外层 Result.message。 */
@Data
public class AgentOpsResult {

    private String op;

    private Long memoId;

    private String status;

    @Schema(description = "服务端生成的确认语，Dify 直接把它发给用户")
    private String message;

    @Schema(description = "true 表示这次是重复请求，业务写入没有再次执行")
    private boolean replayed;

    @Schema(description = "变更后仍未发送的提醒")
    private List<ReminderBrief> reminders;

    @Data
    public static class ReminderBrief {
        private String offsetLabel;
        private LocalDateTime remindAt;
    }
}
