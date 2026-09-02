-- 本地下载进度支撑：downloaded_bytes 记录已下载字节数，前端据此展示进度条。
-- 语句带存在性检查，可安全重试。

SET @v5_schema = DATABASE();

SET @v5_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @v5_schema AND TABLE_NAME = 'eh_galleries' AND COLUMN_NAME = 'downloaded_bytes'
  ), 'SET @v5_noop = 1',
  'ALTER TABLE `eh_galleries` ADD COLUMN `downloaded_bytes` BIGINT NULL AFTER `file_size_mb`')
);
PREPARE v5_statement FROM @v5_sql;
EXECUTE v5_statement;
DEALLOCATE PREPARE v5_statement;
