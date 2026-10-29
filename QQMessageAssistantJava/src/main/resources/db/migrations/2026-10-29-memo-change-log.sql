-- Phase 6：Dify 直连后端的窄写入口
-- 1) memo_change_log：每次备忘变更留痕（AI 与人工都记），event_id 同时承担幂等键。
-- 2) 已有库执行本文件；新库直接用 db/schema.sql。

CREATE TABLE IF NOT EXISTS `memo_change_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `memo_id` BIGINT UNSIGNED NOT NULL,
  `sender_id` BIGINT UNSIGNED NOT NULL,
  `source` VARCHAR(16) NOT NULL COMMENT 'AGENT|VUE',
  `op` VARCHAR(16) NOT NULL COMMENT 'CREATE|UPDATE|CLOSE',
  `event_id` VARCHAR(64) NULL COMMENT '幂等键；人工改动为 NULL',
  `before_json` TEXT NULL,
  `after_json` TEXT NULL,
  `result_json` TEXT NULL,
  `conversation_id` VARCHAR(64) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event` (`event_id`),
  KEY `idx_memo` (`memo_id`),
  KEY `idx_sender_created` (`sender_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='备忘变更留痕 + Agent 幂等台账';
