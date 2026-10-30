-- Phase 7b：修三个「跑起来才会暴露」的问题
--
-- 1) chat_digest_item.uk_chat_period 把 (chat_id, period_start) 定成唯一键，
--    但一个群一小时本来就可能整理出好几条，第二条 insert 直接撞唯一键。
--    「重跑覆盖而非追加」的保证改由「事务内先删后插」来兜（ChatDigestServiceImpl）。
--    同时把 source_msg_ids 从 VARCHAR(512) 放宽到 TEXT：同一话题合并后会有一大串 id。
-- 2) message_log 缺 chat_id 索引，整理调度每小时按 chat_id 扫一遍全表（且表只增不减）。
--
-- 已有库执行本文件；新库直接用 db/schema.sql。
-- 注意：MySQL 的 ADD KEY 不支持 IF NOT EXISTS，本文件只应执行一次。

ALTER TABLE `chat_digest_item`
  DROP INDEX `uk_chat_period`,
  ADD KEY `idx_chat_period` (`chat_id`, `period_start`),
  MODIFY COLUMN `source_msg_ids` TEXT NOT NULL COMMENT 'message_log.id，逗号分隔';

ALTER TABLE `message_log`
  ADD KEY `idx_chat_created` (`chat_id`, `created_at`);
