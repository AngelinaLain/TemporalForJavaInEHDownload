-- COMPLETED_WITH_ERRORS is 21 characters; leave room for future terminal states as well.
ALTER TABLE `eh_visual_refresh_jobs`
  MODIFY COLUMN `status` VARCHAR(32) NOT NULL;
