-- 由 design/database_schema.md 落地；幂等，可重复执行。
CREATE TABLE IF NOT EXISTS `draft` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `type` VARCHAR(16) NOT NULL COMMENT 'REPLY|FILE|MEMO|FACT',
  `sender_id` BIGINT UNSIGNED NOT NULL,
  `chat_id` BIGINT UNSIGNED NULL,
  `title` VARCHAR(255) NULL COMMENT '列表展示用简短标题',
  `payload` JSON NOT NULL COMMENT '各 type 自定结构',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|CONFIRMED|REJECTED',
  `confirmed_at` DATETIME NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_status_created` (`status`, `created_at`),
  KEY `idx_type_status` (`type`, `status`),
  KEY `idx_sender` (`sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一草稿中心';

CREATE TABLE IF NOT EXISTS `message_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id` BIGINT UNSIGNED NOT NULL,
  `chat_id` BIGINT UNSIGNED NULL,
  `direction` VARCHAR(8) NOT NULL COMMENT 'IN|OUT',
  `msg_type` VARCHAR(16) NOT NULL COMMENT 'text|image|file...',
  `content` TEXT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_sender_created` (`sender_id`, `created_at`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一消息日志';

CREATE TABLE IF NOT EXISTS `memo` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id` BIGINT UNSIGNED NOT NULL,
  `chat_id` BIGINT UNSIGNED NULL,
  `title` VARCHAR(255) NOT NULL,
  `content` TEXT NULL,
  `importance` TINYINT UNSIGNED NOT NULL COMMENT '1-5',
  `category` VARCHAR(32) NULL,
  `due_time` DATETIME NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `source_msg_id` BIGINT UNSIGNED NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_sender_status` (`sender_id`, `status`),
  KEY `idx_status_due` (`status`, `due_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='备忘录';

CREATE TABLE IF NOT EXISTS `memo_reminder` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `memo_id` BIGINT UNSIGNED NOT NULL,
  `offset_label` VARCHAR(16) NOT NULL,
  `remind_at` DATETIME NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `sent_at` DATETIME NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_remind` (`status`, `remind_at`),
  KEY `idx_memo` (`memo_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提醒计划';

CREATE TABLE IF NOT EXISTS `collection_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `chat_id` BIGINT UNSIGNED NOT NULL,
  `task_type` VARCHAR(32) NOT NULL,
  `task_key` VARCHAR(128) NOT NULL,
  `window_start` DATETIME NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `replied_at` DATETIME NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_task` (`chat_id`, `task_key`),
  KEY `idx_chat_status` (`chat_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一回复防重状态';

CREATE TABLE IF NOT EXISTS `intercept_rule` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(64) NOT NULL,
  `keywords` VARCHAR(512) NULL,
  `regex` VARCHAR(512) NULL,
  `reply_template` TEXT NULL,
  `priority` INT NOT NULL DEFAULT 0,
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_enabled_priority` (`enabled`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='前置拦截规则';

CREATE TABLE IF NOT EXISTS `profile_fact` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id` BIGINT UNSIGNED NOT NULL,
  `fact_key` VARCHAR(64) NOT NULL,
  `fact_value` VARCHAR(512) NOT NULL,
  `confidence` DECIMAL(3,2) NULL,
  `source` VARCHAR(16) NOT NULL DEFAULT 'AUTO',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sender_key` (`sender_id`, `fact_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='事实记忆';

CREATE TABLE IF NOT EXISTS `style_profile` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id` BIGINT UNSIGNED NOT NULL,
  `profile_prompt` TEXT NOT NULL,
  `last_summarized_at` DATETIME NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sender` (`sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='风格画像';

CREATE TABLE IF NOT EXISTS `app_setting` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `setting_key` VARCHAR(64) NOT NULL,
  `setting_value` VARCHAR(512) NULL,
  `description` VARCHAR(255) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_setting_key` (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='全局配置';

CREATE TABLE IF NOT EXISTS `file_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_ref` VARCHAR(64) NOT NULL,
  `sender_id` BIGINT UNSIGNED NOT NULL,
  `chat_id` BIGINT UNSIGNED NULL,
  `file_name` VARCHAR(255) NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  `detail` JSON NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_ref` (`task_ref`),
  KEY `idx_sender_status` (`sender_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件任务状态镜像';