-- NapCat 掉线补拉的游标：每个会话「已经补到哪一条」。
--
-- 存 NapCat 的 message_seq（单调递增的序号），不是时间戳：
-- 同一秒里可能有多条消息，时间戳既不能排序也不能做「大于游标」的截断。
--
-- 放 Spring 而不是插件本地文件：插件容器重建后游标不能丢，
-- 否则下次补拉会把几百条历史重新拉一遍（虽然有 message_id 去重，但白白打 QQ 接口）。
CREATE TABLE IF NOT EXISTS `napcat_cursor` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `chat_type`        VARCHAR(8)  NOT NULL COMMENT 'GROUP|PRIVATE',
  `chat_id`          BIGINT UNSIGNED NOT NULL COMMENT '群号；私聊存对方 QQ',
  `last_message_seq` BIGINT      NOT NULL DEFAULT 0 COMMENT 'NapCat message_seq，只前进不后退',
  `updated_at`       DATETIME    NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_napcat_cursor` (`chat_type`, `chat_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='NapCat 补拉游标';
