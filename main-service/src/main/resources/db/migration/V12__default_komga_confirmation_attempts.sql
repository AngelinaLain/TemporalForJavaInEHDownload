-- V9 skipped the alteration when the column already existed, leaving some upgraded databases
-- with a NOT NULL column but no DEFAULT. Batch inserts explicitly include this column.
UPDATE `eh_galleries`
SET `komga_confirmation_attempts` = 0
WHERE `komga_confirmation_attempts` IS NULL;

ALTER TABLE `eh_galleries`
    MODIFY COLUMN `komga_confirmation_attempts` INT NOT NULL DEFAULT 0;
