ALTER TABLE `eh_galleries`
  ADD COLUMN `series_cleanup_path` VARCHAR(500) NULL AFTER `series_sync_signature`,
  ADD COLUMN `series_cleanup_filename` TEXT NULL AFTER `series_cleanup_path`;
