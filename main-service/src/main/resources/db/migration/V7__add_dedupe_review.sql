-- Persist gray-zone deduplication reviews. Human decisions are durable inputs
-- to later automatic clustering and therefore survive future crawls/restarts.
CREATE TABLE IF NOT EXISTS `eh_dedupe_reviews` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `candidate_key` CHAR(64) NOT NULL,
  `left_gid` BIGINT NOT NULL,
  `right_gid` BIGINT NOT NULL,
  `match_score` TINYINT UNSIGNED NOT NULL,
  `match_reason` VARCHAR(1000) NULL,
  `recommended_gid` BIGINT NOT NULL,
  `decision` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  `preferred_gid` BIGINT NULL,
  `reviewed_by` VARCHAR(100) NULL,
  `reviewed_at` TIMESTAMP NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_eh_dedupe_review_pair` (`left_gid`, `right_gid`),
  KEY `idx_eh_dedupe_review_status` (`decision`, `created_at`),
  KEY `idx_eh_dedupe_review_candidate` (`candidate_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
