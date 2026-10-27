-- Support date-scoped statistics and the default preferred-version gallery ordering.
-- Retry-safe on MySQL versions without ADD INDEX IF NOT EXISTS.
SET @browse_schema = DATABASE();

SET @browse_index_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @browse_schema AND TABLE_NAME = 'eh_galleries'
      AND INDEX_NAME = 'idx_gallery_crawled_gid'
  ), 'DO 0',
  'ALTER TABLE eh_galleries ADD INDEX idx_gallery_crawled_gid (crawled_at, gid)')
);
PREPARE browse_index_statement FROM @browse_index_sql;
EXECUTE browse_index_statement;
DEALLOCATE PREPARE browse_index_statement;

SET @browse_index_sql = (
  SELECT IF(EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @browse_schema AND TABLE_NAME = 'eh_galleries'
      AND INDEX_NAME = 'idx_gallery_preferred_crawled_gid'
  ), 'DO 0',
  'ALTER TABLE eh_galleries ADD INDEX idx_gallery_preferred_crawled_gid (duplicate_of_gid, crawled_at, gid)')
);
PREPARE browse_index_statement FROM @browse_index_sql;
EXECUTE browse_index_statement;
DEALLOCATE PREPARE browse_index_statement;
