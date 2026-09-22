ALTER TABLE `eh_galleries`
  ADD COLUMN `storage_path` VARCHAR(500) NULL AFTER `filename`,
  ADD COLUMN `series_sync_signature` VARCHAR(500) NULL AFTER `storage_path`;

CREATE INDEX `idx_eh_galleries_storage_path` ON `eh_galleries` (`storage_path`);
