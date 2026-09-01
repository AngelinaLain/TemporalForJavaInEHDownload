-- Upgrade gallery deduplication from exact fingerprint equality to
-- candidate retrieval + explainable multi-signal scoring.

SET @dedupe_schema = DATABASE();

SET @dedupe_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @dedupe_schema AND TABLE_NAME = 'eh_galleries' AND COLUMN_NAME = 'candidate_key'
  ), 'SET @dedupe_noop = 1',
  'ALTER TABLE `eh_galleries` ADD COLUMN `candidate_key` CHAR(64) NULL AFTER `dedupe_key`')
);
PREPARE dedupe_statement FROM @dedupe_sql;
EXECUTE dedupe_statement;
DEALLOCATE PREPARE dedupe_statement;

-- A tiny lock-bucket table lets concurrent parent workflows serialize the
-- preferred-version decision with SELECT ... FOR UPDATE. It is deliberately
-- separate from eh_galleries because multiple gallery editions share a key.
CREATE TABLE IF NOT EXISTS `eh_dedupe_locks` (
  `candidate_key` CHAR(64) NOT NULL,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`candidate_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @dedupe_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @dedupe_schema AND TABLE_NAME = 'eh_galleries' AND COLUMN_NAME = 'dedupe_match_score'
  ), 'SET @dedupe_noop = 1',
  'ALTER TABLE `eh_galleries` ADD COLUMN `dedupe_match_score` TINYINT UNSIGNED NULL AFTER `dedupe_confidence`')
);
PREPARE dedupe_statement FROM @dedupe_sql;
EXECUTE dedupe_statement;
DEALLOCATE PREPARE dedupe_statement;

SET @dedupe_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @dedupe_schema AND TABLE_NAME = 'eh_galleries' AND COLUMN_NAME = 'dedupe_match_reason'
  ), 'SET @dedupe_noop = 1',
  'ALTER TABLE `eh_galleries` ADD COLUMN `dedupe_match_reason` VARCHAR(1000) NULL AFTER `dedupe_match_score`')
);
PREPARE dedupe_statement FROM @dedupe_sql;
EXECUTE dedupe_statement;
DEALLOCATE PREPARE dedupe_statement;

SET @dedupe_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @dedupe_schema AND TABLE_NAME = 'eh_galleries' AND COLUMN_NAME = 'dedupe_algorithm_version'
  ), 'SET @dedupe_noop = 1',
  'ALTER TABLE `eh_galleries` ADD COLUMN `dedupe_algorithm_version` INT NULL AFTER `dedupe_match_reason`')
);
PREPARE dedupe_statement FROM @dedupe_sql;
EXECUTE dedupe_statement;
DEALLOCATE PREPARE dedupe_statement;

SET @dedupe_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @dedupe_schema AND TABLE_NAME = 'eh_galleries' AND INDEX_NAME = 'idx_eh_galleries_candidate_key'
  ), 'SET @dedupe_noop = 1',
  'ALTER TABLE `eh_galleries` ADD INDEX `idx_eh_galleries_candidate_key` (`candidate_key`)')
);
PREPARE dedupe_statement FROM @dedupe_sql;
EXECUTE dedupe_statement;
DEALLOCATE PREPARE dedupe_statement;
