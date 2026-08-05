ALTER TABLE `eh_galleries`
  ADD COLUMN `summary` VARCHAR(1000) NULL AFTER `file_size_mb`,
  ADD INDEX `idx_eh_galleries_token` (`token`),
  ADD INDEX `idx_eh_galleries_status_crawled_at` (`download_status`, `crawled_at`);
