ALTER TABLE rss_subscription
 ADD COLUMN collection_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN collection_id BIGINT NULL;

CREATE TABLE rss_collection_task (
 subscription_id VARCHAR(36) NOT NULL,
 gid BIGINT NOT NULL,
 collection_id BIGINT NOT NULL,
 revision INT NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'WAITING',
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at BIGINT NOT NULL DEFAULT 0,
 updated_at BIGINT NOT NULL,
 last_error VARCHAR(2000) NULL,
 placement_signature VARCHAR(500) NULL,
 PRIMARY KEY(subscription_id, gid, collection_id),
 INDEX idx_rss_collection_due(status, next_attempt_at),
 INDEX idx_rss_collection_gid(gid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
