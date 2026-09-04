-- Sampled page perceptual hashes, pairwise visual evidence, and restartable history refresh jobs.
CREATE TABLE IF NOT EXISTS `eh_gallery_page_hashes` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `gid` BIGINT NOT NULL,
  `page_index` INT NOT NULL,
  `page_name` VARCHAR(500) NULL,
  `source` VARCHAR(20) NOT NULL,
  `perceptual_hash` CHAR(16) NOT NULL,
  `center_hash` CHAR(16) NOT NULL,
  `quality` TINYINT UNSIGNED NULL,
  `width` INT NULL,
  `height` INT NULL,
  `algorithm_version` INT NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_gallery_page_hash` (`gid`, `page_index`, `algorithm_version`),
  KEY `idx_gallery_hash_gid_version` (`gid`, `algorithm_version`),
  KEY `idx_gallery_hash_phash` (`perceptual_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `eh_visual_matches` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `left_gid` BIGINT NOT NULL,
  `right_gid` BIGINT NOT NULL,
  `similarity` TINYINT UNSIGNED NOT NULL,
  `matched_pages` INT NOT NULL,
  `left_samples` INT NOT NULL,
  `right_samples` INT NOT NULL,
  `sample_coverage` TINYINT UNSIGNED NOT NULL,
  `order_consistency` TINYINT UNSIGNED NOT NULL,
  `recommended_gid` BIGINT NULL,
  `quality_delta` TINYINT UNSIGNED NOT NULL,
  `reason` VARCHAR(1000) NULL,
  `algorithm_version` INT NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_visual_match_pair_version` (`left_gid`, `right_gid`, `algorithm_version`),
  KEY `idx_visual_match_similarity` (`similarity`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `eh_visual_refresh_jobs` (
  `id` CHAR(36) NOT NULL,
  `status` VARCHAR(20) NOT NULL,
  `force_refresh` BOOLEAN NOT NULL DEFAULT FALSE,
  `algorithm_version` INT NOT NULL,
  `total` INT NOT NULL DEFAULT 0,
  `processed` INT NOT NULL DEFAULT 0,
  `succeeded` INT NOT NULL DEFAULT 0,
  `failed` INT NOT NULL DEFAULT 0,
  `current_gid` BIGINT NULL,
  `last_error` VARCHAR(1000) NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `started_at` TIMESTAMP NULL,
  `finished_at` TIMESTAMP NULL,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_visual_refresh_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @v11_schema = DATABASE();

SET @v11_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @v11_schema AND TABLE_NAME = 'eh_dedupe_reviews' AND COLUMN_NAME = 'visual_similarity'
  ), 'SET @v11_noop = 1',
  'ALTER TABLE `eh_dedupe_reviews` ADD COLUMN `visual_similarity` TINYINT UNSIGNED NULL AFTER `match_reason`')
);
PREPARE v11_statement FROM @v11_sql;
EXECUTE v11_statement;
DEALLOCATE PREPARE v11_statement;

SET @v11_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @v11_schema AND TABLE_NAME = 'eh_dedupe_reviews' AND COLUMN_NAME = 'visual_matched_pages'
  ), 'SET @v11_noop = 1',
  'ALTER TABLE `eh_dedupe_reviews` ADD COLUMN `visual_matched_pages` INT NULL AFTER `visual_similarity`')
);
PREPARE v11_statement FROM @v11_sql;
EXECUTE v11_statement;
DEALLOCATE PREPARE v11_statement;

SET @v11_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @v11_schema AND TABLE_NAME = 'eh_dedupe_reviews' AND COLUMN_NAME = 'visual_sample_coverage'
  ), 'SET @v11_noop = 1',
  'ALTER TABLE `eh_dedupe_reviews` ADD COLUMN `visual_sample_coverage` TINYINT UNSIGNED NULL AFTER `visual_matched_pages`')
);
PREPARE v11_statement FROM @v11_sql;
EXECUTE v11_statement;
DEALLOCATE PREPARE v11_statement;

SET @v11_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @v11_schema AND TABLE_NAME = 'eh_dedupe_reviews' AND COLUMN_NAME = 'visual_order_consistency'
  ), 'SET @v11_noop = 1',
  'ALTER TABLE `eh_dedupe_reviews` ADD COLUMN `visual_order_consistency` TINYINT UNSIGNED NULL AFTER `visual_sample_coverage`')
);
PREPARE v11_statement FROM @v11_sql;
EXECUTE v11_statement;
DEALLOCATE PREPARE v11_statement;

SET @v11_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @v11_schema AND TABLE_NAME = 'eh_dedupe_reviews' AND COLUMN_NAME = 'visual_recommended_gid'
  ), 'SET @v11_noop = 1',
  'ALTER TABLE `eh_dedupe_reviews` ADD COLUMN `visual_recommended_gid` BIGINT NULL AFTER `visual_order_consistency`')
);
PREPARE v11_statement FROM @v11_sql;
EXECUTE v11_statement;
DEALLOCATE PREPARE v11_statement;

SET @v11_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @v11_schema AND TABLE_NAME = 'eh_dedupe_reviews' AND COLUMN_NAME = 'visual_quality_delta'
  ), 'SET @v11_noop = 1',
  'ALTER TABLE `eh_dedupe_reviews` ADD COLUMN `visual_quality_delta` TINYINT UNSIGNED NULL AFTER `visual_recommended_gid`')
);
PREPARE v11_statement FROM @v11_sql;
EXECUTE v11_statement;
DEALLOCATE PREPARE v11_statement;

SET @v11_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @v11_schema AND TABLE_NAME = 'eh_dedupe_reviews' AND COLUMN_NAME = 'visual_reason'
  ), 'SET @v11_noop = 1',
  'ALTER TABLE `eh_dedupe_reviews` ADD COLUMN `visual_reason` VARCHAR(1000) NULL AFTER `visual_quality_delta`')
);
PREPARE v11_statement FROM @v11_sql;
EXECUTE v11_statement;
DEALLOCATE PREPARE v11_statement;

SET @v11_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @v11_schema AND TABLE_NAME = 'eh_dedupe_reviews' AND COLUMN_NAME = 'visual_algorithm_version'
  ), 'SET @v11_noop = 1',
  'ALTER TABLE `eh_dedupe_reviews` ADD COLUMN `visual_algorithm_version` INT NULL AFTER `visual_reason`')
);
PREPARE v11_statement FROM @v11_sql;
EXECUTE v11_statement;
DEALLOCATE PREPARE v11_statement;
