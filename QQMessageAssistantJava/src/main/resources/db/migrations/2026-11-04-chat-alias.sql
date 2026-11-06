-- 会话展示名：群号/私聊对方 QQ → 用户起的名字。
-- 与 chat_setting 分开：后者是开关，且「发现行」是虚拟的、不落库。
CREATE TABLE IF NOT EXISTS `chat_alias` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `chat_id`    BIGINT UNSIGNED NOT NULL COMMENT '群号；私聊存对方 QQ',
  `kind`       VARCHAR(8)  NOT NULL COMMENT 'GROUP|PRIVATE',
  `aliases`    VARCHAR(255) NOT NULL DEFAULT '' COMMENT '逗号分隔；空串 = 用户拒绝命名',
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_alias` (`chat_id`, `kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话展示名';
