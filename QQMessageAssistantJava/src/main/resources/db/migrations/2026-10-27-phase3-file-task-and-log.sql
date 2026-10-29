-- Phase 3 迁移：file_task 扩展字段 + message_log 元数据。
-- 仅对「已存在的库」执行一次；新库直接用 db/schema.sql 即可（已包含这些列）。
-- 执行：mysql -h <host> -P <port> -u <user> -p qq_assistant < 本文件

ALTER TABLE `file_task`
  ADD COLUMN `conversation_id` VARCHAR(64) NULL COMMENT 'Dify conversation_id' AFTER `chat_id`,
  ADD COLUMN `request` TEXT NULL COMMENT '用户原始填写要求' AFTER `file_name`,
  ADD COLUMN `pending_request` TEXT NULL COMMENT '等待文件时挂起的要求' AFTER `request`,
  ADD COLUMN `missing` JSON NULL COMMENT 'Dify 判定仍缺的字段' AFTER `status`,
  ADD COLUMN `confidence` DECIMAL(3,2) NULL COMMENT '填写置信度 0-1' AFTER `missing`,
  ADD COLUMN `needs_confirm` TINYINT(1) NULL COMMENT '是否强制人工确认' AFTER `confidence`,
  ADD COLUMN `file_ref` VARCHAR(512) NULL COMMENT '输入文件引用 local://...' AFTER `needs_confirm`,
  ADD COLUMN `output_ref` VARCHAR(512) NULL COMMENT '产物文件引用 local://...' AFTER `file_ref`,
  ADD COLUMN `source_message_id` BIGINT UNSIGNED NULL COMMENT '源 QQ 消息 id' AFTER `output_ref`,
  ADD COLUMN `idempotency_key` VARCHAR(128) NULL COMMENT '重复投递去重' AFTER `source_message_id`,
  ADD KEY `idx_conversation` (`conversation_id`);

ALTER TABLE `message_log`
  ADD COLUMN `conversation_id` VARCHAR(64) NULL COMMENT 'Dify conversation_id' AFTER `chat_id`,
  ADD COLUMN `message_id` VARCHAR(64) NULL COMMENT '平台消息 id' AFTER `conversation_id`,
  ADD COLUMN `dedupe_key` VARCHAR(128) NULL COMMENT '幂等键，重复写入被忽略' AFTER `message_id`,
  ADD UNIQUE KEY `uk_dedupe` (`dedupe_key`),
  ADD KEY `idx_conversation` (`conversation_id`);
