-- RSS 归合集前先匹配已有画廊：记录实际归位的画廊、匹配来源与人工复核关联。
ALTER TABLE rss_collection_task
  ADD COLUMN placement_gid BIGINT NULL COMMENT '实际归入合集的画廊 GID；匹配到既有画廊时与 gid 不同',
  ADD COLUMN match_source VARCHAR(24) NULL COMMENT 'SELF 订阅画廊自身 / DUPLICATE 去重重复关系 / AUTO_MATCH 候选组自动匹配',
  ADD COLUMN match_reason VARCHAR(500) NULL COMMENT '匹配依据或保持原样的原因',
  ADD COLUMN review_id BIGINT NULL COMMENT '等待人工复核时关联的去重审核记录',
  ADD INDEX idx_rss_collection_sub_status (subscription_id, status);
