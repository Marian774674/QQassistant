-- Phase 8：历史查询的数据底座（人物表 + 事实归纳）
-- 1) contact：sender_id ↔ 别名/身份，解决「小明是谁」；USER 行永不被夜间任务覆盖。
-- 2) memory_note：从聊天里抽出的「谁 · 什么属性 · 什么值」，每条都带 message_log.id 证据。
-- 已有库执行本文件；新库直接用 db/schema.sql。

CREATE TABLE IF NOT EXISTS `contact` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id`  BIGINT UNSIGNED NOT NULL,
  `alias`      VARCHAR(64)  NOT NULL COMMENT '用户/模型认出来的称呼',
  `relation`   VARCHAR(64)  NULL COMMENT '同学|老师|室友…',
  `importance` TINYINT      NOT NULL DEFAULT 0 COMMENT '0=普通 1=一般 2=重要',
  `note`       VARCHAR(255) NULL,
  `source`     VARCHAR(8)   NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO|USER',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sender_alias` (`sender_id`, `alias`),
  KEY `idx_alias` (`alias`),
  KEY `idx_sender` (`sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='认识的人';

-- 不加 UNIQUE(subject,predicate,value)：subject 可空（=关于主人自己），
-- MySQL 唯一索引允许多个 NULL，去重交给代码；也避免长 value 撑爆索引。
CREATE TABLE IF NOT EXISTS `memory_note` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `subject_sender_id` BIGINT UNSIGNED NULL COMMENT '主体；空=关于主人自己',
  `predicate`         VARCHAR(64)  NOT NULL COMMENT '受控词表',
  `value`             VARCHAR(255) NOT NULL,
  `happened_at`       DATETIME     NULL,
  `source_msg_ids`    VARCHAR(512) NOT NULL COMMENT '逗号分隔的 message_log.id',
  `confidence`        DECIMAL(3,2) NULL,
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_subject_pred` (`subject_sender_id`, `predicate`),
  KEY `idx_predicate` (`predicate`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='事实归纳';
