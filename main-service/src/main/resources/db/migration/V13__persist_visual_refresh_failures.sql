CREATE TABLE IF NOT EXISTS `eh_visual_refresh_failures` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `job_id` CHAR(36) NOT NULL,
  `gid` BIGINT NOT NULL,
  `error` VARCHAR(1000) NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_visual_refresh_failure_job_gid` (`job_id`, `gid`),
  KEY `idx_visual_refresh_failure_job` (`job_id`),
  CONSTRAINT `fk_visual_refresh_failure_job`
    FOREIGN KEY (`job_id`) REFERENCES `eh_visual_refresh_jobs` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
