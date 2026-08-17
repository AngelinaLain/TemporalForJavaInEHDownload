-- 增量同步与完整性校验支撑：
-- 1. eh_galleries.updated_at：Komga 增量同步的时间戳依据（MetaObjectHandler 填充 + DB 兜底）；
-- 2. app_sync_checkpoint：各同步任务的检查点（首次全量，之后增量）；
-- 3. PARTIAL（不完整）状态不需要改表，download_status 为 VARCHAR 直接可存。
-- 全部语句带存在性检查，可安全重试。

SET @v4_schema = DATABASE();

SET @v4_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @v4_schema AND TABLE_NAME = 'eh_galleries' AND COLUMN_NAME = 'updated_at'
  ), 'SET @v4_noop = 1',
  'ALTER TABLE `eh_galleries` ADD COLUMN `updated_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `summary`')
);
PREPARE v4_statement FROM @v4_sql;
EXECUTE v4_statement;
DEALLOCATE PREPARE v4_statement;

SET @v4_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @v4_schema AND TABLE_NAME = 'eh_galleries' AND INDEX_NAME = 'idx_eh_galleries_updated_at'
  ), 'SET @v4_noop = 1',
  'ALTER TABLE `eh_galleries` ADD INDEX `idx_eh_galleries_updated_at` (`updated_at`)')
);
PREPARE v4_statement FROM @v4_sql;
EXECUTE v4_statement;
DEALLOCATE PREPARE v4_statement;

SET @v4_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @v4_schema AND TABLE_NAME = 'app_sync_checkpoint'
  ), 'SET @v4_noop = 1',
  'CREATE TABLE `app_sync_checkpoint` (
     `sync_key` VARCHAR(64) NOT NULL COMMENT ''同步任务标识'',
     `last_synced_at` DATETIME NULL COMMENT ''上次同步完成时间'',
     `payload` VARCHAR(255) NULL COMMENT ''附加状态（如元数据哈希）'',
     PRIMARY KEY (`sync_key`)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci')
);
PREPARE v4_statement FROM @v4_sql;
EXECUTE v4_statement;
DEALLOCATE PREPARE v4_statement;
