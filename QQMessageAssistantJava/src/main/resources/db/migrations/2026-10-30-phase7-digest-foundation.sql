-- Phase 7：消息整理的数据底座
-- 1) message_log 补昵称/群名（整理结果要写「谁说了什么」，只有 id 没法读）；
--    reply_kind 本期只建列，留给后续「自动回复」区分 TASK|CHAT。
-- 2) chat_setting：每个会话的开关（自动回复 / 纳入整理），没这行 = 全关。
-- 3) chat_digest_item：每群每小时的整理结果，uk_chat_period 保证重跑覆盖。
-- 已有库执行本文件；新库直接用 db/schema.sql。

ALTER TABLE `message_log`
  ADD COLUMN `sender_name` VARCHAR(64) NULL COMMENT '发送者昵称' AFTER `sender_id`,
  ADD COLUMN `chat_name` VARCHAR(128) NULL COMMENT '群名；私聊为 NULL' AFTER `chat_id`,
  ADD COLUMN `reply_kind` VARCHAR(8) NULL COMMENT 'OUT 专用：TASK|CHAT' AFTER `direction`;

CREATE TABLE IF NOT EXISTS `chat_setting` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `chat_id`            BIGINT UNSIGNED NOT NULL COMMENT '群号；私聊存对方 sender_id',
  `kind`               VARCHAR(8)  NOT NULL COMMENT 'GROUP|PRIVATE',
  `chat_name`          VARCHAR(128) NULL,
  `auto_reply_enabled` TINYINT(1)  NOT NULL DEFAULT 0,
  `digest_enabled`     TINYINT(1)  NOT NULL DEFAULT 0,
  `created_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat` (`chat_id`, `kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话开关';

CREATE TABLE IF NOT EXISTS `chat_digest_item` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `chat_id`        BIGINT UNSIGNED NOT NULL,
  `chat_name`      VARCHAR(128) NULL,
  `period_start`   DATETIME NOT NULL,
  `period_end`     DATETIME NOT NULL,
  `summary`        VARCHAR(512) NOT NULL,
  `importance`     TINYINT      NOT NULL COMMENT '1-5',
  `keywords`       VARCHAR(255) NULL,
  `mentions_me`    TINYINT(1)   NOT NULL DEFAULT 0,
  `source_msg_ids` VARCHAR(512) NOT NULL COMMENT 'message_log.id，逗号分隔',
  `created_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_period` (`chat_id`, `period_start`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息整理';
