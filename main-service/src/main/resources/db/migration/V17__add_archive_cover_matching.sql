ALTER TABLE `eh_archive_sync_reviews`
  ADD COLUMN `cover_scores` LONGTEXT NULL AFTER `candidate_filenames`,
  ADD COLUMN `cover_status` VARCHAR(32) NULL AFTER `cover_scores`,
  ADD COLUMN `cover_message` VARCHAR(1000) NULL AFTER `cover_status`,
  ADD COLUMN `cover_checked_at` TIMESTAMP NULL AFTER `cover_message`;
