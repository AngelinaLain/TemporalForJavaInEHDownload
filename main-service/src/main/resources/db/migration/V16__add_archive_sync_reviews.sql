CREATE TABLE IF NOT EXISTS `eh_archive_sync_reviews` (
  `gid` BIGINT NOT NULL,
  `title` VARCHAR(1000) NULL,
  `expected_filename` VARCHAR(500) NOT NULL,
  `selected_filename` VARCHAR(500) NULL,
  `candidate_filenames` LONGTEXT NULL,
  `match_type` VARCHAR(20) NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  `message` VARCHAR(1000) NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`gid`),
  KEY `idx_archive_sync_review_status` (`status`, `match_type`, `gid`),
  CONSTRAINT `fk_archive_sync_review_gallery`
    FOREIGN KEY (`gid`) REFERENCES `eh_galleries` (`gid`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
