-- 5.5 Token 用量账本：一次模型调用一行，只追加不修改。
-- 工作流应用只回 total_tokens，不拆 prompt/completion；chat 应用才有 usage 明细。
-- 所以 total_tokens 是唯一可跨来源比较的字段，拆出来的两项可能为 0。
CREATE TABLE IF NOT EXISTS `token_usage` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `source`            VARCHAR(12)  NOT NULL DEFAULT 'spring' COMMENT 'spring|plugin',
  `task`              VARCHAR(32)  NOT NULL COMMENT 'digest|extract|ask|chat|file …',
  `model`             VARCHAR(64)  NULL COMMENT 'Dify 不回传模型名，由配置标注；空=未标注',
  `prompt_tokens`     INT          NOT NULL DEFAULT 0,
  `completion_tokens` INT          NOT NULL DEFAULT 0,
  `total_tokens`      INT          NOT NULL DEFAULT 0,
  `chat_id`           BIGINT       NULL COMMENT '群号；私聊与 Spring 侧任务可空',
  `created_at`        DATETIME     NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_usage_created` (`created_at`),
  KEY `idx_usage_task_created` (`task`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模型调用 token 用量（只读账本）';
