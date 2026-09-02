-- Komga 入库确认的持久化进度，供失败复核与补偿页面使用。

SET @komga_schema = DATABASE();

SET @komga_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @komga_schema AND TABLE_NAME = 'eh_galleries'
      AND COLUMN_NAME = 'komga_confirmation_attempts'
  ), 'SET @komga_noop = 1',
  'ALTER TABLE `eh_galleries` ADD COLUMN `komga_confirmation_attempts` INT NOT NULL DEFAULT 0 AFTER `komga_book_id`')
);
PREPARE komga_statement FROM @komga_sql;
EXECUTE komga_statement;
DEALLOCATE PREPARE komga_statement;

SET @komga_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @komga_schema AND TABLE_NAME = 'eh_galleries'
      AND COLUMN_NAME = 'komga_last_confirmation_at'
  ), 'SET @komga_noop = 1',
  'ALTER TABLE `eh_galleries` ADD COLUMN `komga_last_confirmation_at` TIMESTAMP NULL AFTER `komga_confirmation_attempts`')
);
PREPARE komga_statement FROM @komga_sql;
EXECUTE komga_statement;
DEALLOCATE PREPARE komga_statement;

SET @komga_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @komga_schema AND TABLE_NAME = 'eh_galleries'
      AND COLUMN_NAME = 'komga_confirmation_reason'
  ), 'SET @komga_noop = 1',
  'ALTER TABLE `eh_galleries` ADD COLUMN `komga_confirmation_reason` VARCHAR(1000) NULL AFTER `komga_last_confirmation_at`')
);
PREPARE komga_statement FROM @komga_sql;
EXECUTE komga_statement;
DEALLOCATE PREPARE komga_statement;

SET @komga_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @komga_schema AND TABLE_NAME = 'eh_galleries'
      AND COLUMN_NAME = 'komga_candidate_book_ids'
  ), 'SET @komga_noop = 1',
  'ALTER TABLE `eh_galleries` ADD COLUMN `komga_candidate_book_ids` VARCHAR(2000) NULL AFTER `komga_confirmation_reason`')
);
PREPARE komga_statement FROM @komga_sql;
EXECUTE komga_statement;
DEALLOCATE PREPARE komga_statement;
