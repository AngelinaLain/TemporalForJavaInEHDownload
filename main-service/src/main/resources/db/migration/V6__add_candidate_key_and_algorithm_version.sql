-- 去重 v2 支撑：candidate_key（宽松候选阻塞键）与 dedupe_algorithm_version（算法版本）。
-- 全部语句带存在性检查，可安全重试。

SET @v6_schema = DATABASE();

SET @v6_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @v6_schema AND TABLE_NAME = 'eh_galleries' AND COLUMN_NAME = 'candidate_key'
  ), 'SET @v6_noop = 1',
  'ALTER TABLE `eh_galleries` ADD COLUMN `candidate_key` CHAR(64) NULL AFTER `dedupe_key`')
);
PREPARE v6_statement FROM @v6_sql;
EXECUTE v6_statement;
DEALLOCATE PREPARE v6_statement;

SET @v6_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @v6_schema AND TABLE_NAME = 'eh_galleries' AND COLUMN_NAME = 'dedupe_algorithm_version'
  ), 'SET @v6_noop = 1',
  'ALTER TABLE `eh_galleries` ADD COLUMN `dedupe_algorithm_version` INT NULL AFTER `dedupe_confidence`')
);
PREPARE v6_statement FROM @v6_sql;
EXECUTE v6_statement;
DEALLOCATE PREPARE v6_statement;

SET @v6_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @v6_schema AND TABLE_NAME = 'eh_galleries' AND INDEX_NAME = 'idx_eh_galleries_candidate_key'
  ), 'SET @v6_noop = 1',
  'ALTER TABLE `eh_galleries` ADD INDEX `idx_eh_galleries_candidate_key` (`candidate_key`)')
);
PREPARE v6_statement FROM @v6_sql;
EXECUTE v6_statement;
DEALLOCATE PREPARE v6_statement;
