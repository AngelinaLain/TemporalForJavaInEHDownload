-- Store a logical work fingerprint and the preferred translation relationship.
-- The checks keep this migration retry-safe on older MySQL versions that do not
-- implement ALTER TABLE ... IF NOT EXISTS.

SET @dedupe_schema = DATABASE();

SET @dedupe_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @dedupe_schema AND TABLE_NAME = 'eh_galleries' AND COLUMN_NAME = 'original_title'
  ), 'SET @dedupe_noop = 1',
  'ALTER TABLE `eh_galleries` ADD COLUMN `original_title` VARCHAR(500) NULL AFTER `title`')
);
PREPARE dedupe_statement FROM @dedupe_sql;
EXECUTE dedupe_statement;
DEALLOCATE PREPARE dedupe_statement;

SET @dedupe_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @dedupe_schema AND TABLE_NAME = 'eh_galleries' AND COLUMN_NAME = 'page_count'
  ), 'SET @dedupe_noop = 1',
  'ALTER TABLE `eh_galleries` ADD COLUMN `page_count` INT NULL AFTER `original_title`')
);
PREPARE dedupe_statement FROM @dedupe_sql;
EXECUTE dedupe_statement;
DEALLOCATE PREPARE dedupe_statement;

SET @dedupe_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @dedupe_schema AND TABLE_NAME = 'eh_galleries' AND COLUMN_NAME = 'rating'
  ), 'SET @dedupe_noop = 1',
  'ALTER TABLE `eh_galleries` ADD COLUMN `rating` DOUBLE NULL AFTER `page_count`')
);
PREPARE dedupe_statement FROM @dedupe_sql;
EXECUTE dedupe_statement;
DEALLOCATE PREPARE dedupe_statement;

SET @dedupe_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @dedupe_schema AND TABLE_NAME = 'eh_galleries' AND COLUMN_NAME = 'dedupe_key'
  ), 'SET @dedupe_noop = 1',
  'ALTER TABLE `eh_galleries` ADD COLUMN `dedupe_key` CHAR(64) NULL AFTER `rating`')
);
PREPARE dedupe_statement FROM @dedupe_sql;
EXECUTE dedupe_statement;
DEALLOCATE PREPARE dedupe_statement;

SET @dedupe_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @dedupe_schema AND TABLE_NAME = 'eh_galleries' AND COLUMN_NAME = 'duplicate_of_gid'
  ), 'SET @dedupe_noop = 1',
  'ALTER TABLE `eh_galleries` ADD COLUMN `duplicate_of_gid` BIGINT NULL AFTER `dedupe_key`')
);
PREPARE dedupe_statement FROM @dedupe_sql;
EXECUTE dedupe_statement;
DEALLOCATE PREPARE dedupe_statement;

SET @dedupe_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @dedupe_schema AND TABLE_NAME = 'eh_galleries' AND COLUMN_NAME = 'dedupe_confidence'
  ), 'SET @dedupe_noop = 1',
  'ALTER TABLE `eh_galleries` ADD COLUMN `dedupe_confidence` TINYINT UNSIGNED NULL AFTER `duplicate_of_gid`')
);
PREPARE dedupe_statement FROM @dedupe_sql;
EXECUTE dedupe_statement;
DEALLOCATE PREPARE dedupe_statement;

SET @dedupe_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @dedupe_schema AND TABLE_NAME = 'eh_galleries' AND INDEX_NAME = 'idx_eh_galleries_dedupe_key'
  ), 'SET @dedupe_noop = 1',
  'ALTER TABLE `eh_galleries` ADD INDEX `idx_eh_galleries_dedupe_key` (`dedupe_key`)')
);
PREPARE dedupe_statement FROM @dedupe_sql;
EXECUTE dedupe_statement;
DEALLOCATE PREPARE dedupe_statement;

SET @dedupe_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @dedupe_schema AND TABLE_NAME = 'eh_galleries' AND INDEX_NAME = 'idx_eh_galleries_duplicate_of_gid'
  ), 'SET @dedupe_noop = 1',
  'ALTER TABLE `eh_galleries` ADD INDEX `idx_eh_galleries_duplicate_of_gid` (`duplicate_of_gid`)')
);
PREPARE dedupe_statement FROM @dedupe_sql;
EXECUTE dedupe_statement;
DEALLOCATE PREPARE dedupe_statement;
