-- Existing installations may already have some of these objects. Use
-- information_schema checks instead of MySQL 8.0.29+ "IF NOT EXISTS" syntax
-- so the migration can safely run on older MySQL versions too.

SET @gallery_schema = DATABASE();

SET @add_summary_sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @gallery_schema
        AND TABLE_NAME = 'eh_galleries'
        AND COLUMN_NAME = 'summary'
    ),
    'DO 0',
    'ALTER TABLE `eh_galleries` ADD COLUMN `summary` VARCHAR(1000) NULL AFTER `file_size_mb`'
  )
);
PREPARE add_summary_statement FROM @add_summary_sql;
EXECUTE add_summary_statement;
DEALLOCATE PREPARE add_summary_statement;

SET @add_token_index_sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @gallery_schema
        AND TABLE_NAME = 'eh_galleries'
        AND INDEX_NAME = 'idx_eh_galleries_token'
    ),
    'DO 0',
    'ALTER TABLE `eh_galleries` ADD INDEX `idx_eh_galleries_token` (`token`)'
  )
);
PREPARE add_token_index_statement FROM @add_token_index_sql;
EXECUTE add_token_index_statement;
DEALLOCATE PREPARE add_token_index_statement;

SET @add_status_crawled_index_sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @gallery_schema
        AND TABLE_NAME = 'eh_galleries'
        AND INDEX_NAME = 'idx_eh_galleries_status_crawled_at'
    ),
    'DO 0',
    'ALTER TABLE `eh_galleries` ADD INDEX `idx_eh_galleries_status_crawled_at` (`download_status`, `crawled_at`)'
  )
);
PREPARE add_status_crawled_index_statement FROM @add_status_crawled_index_sql;
EXECUTE add_status_crawled_index_statement;
DEALLOCATE PREPARE add_status_crawled_index_statement;