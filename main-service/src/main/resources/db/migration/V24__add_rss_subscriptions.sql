CREATE TABLE rss_subscription (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 revision INT NOT NULL DEFAULT 1,
 config_json LONGTEXT NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 auto_download BOOLEAN NOT NULL DEFAULT TRUE,
 deleted BOOLEAN NOT NULL DEFAULT FALSE,
 baseline_at BIGINT NOT NULL,
 next_poll_at BIGINT NOT NULL,
 last_success_at BIGINT NULL,
 coverage_at BIGINT NULL,
 last_backfill_at BIGINT NULL,
 scan_cursor TEXT NULL,
 scan_started_at BIGINT NULL,
 scan_before_at BIGINT NULL,
 scan_pages INT NOT NULL DEFAULT 0,
 last_error VARCHAR(2000) NULL,
 error_count INT NOT NULL DEFAULT 0,
 created_at BIGINT NOT NULL,
 INDEX idx_rss_due(enabled, deleted, next_poll_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rss_subscription_revision (
 subscription_id VARCHAR(36) NOT NULL,
 revision INT NOT NULL,
 config_json LONGTEXT NOT NULL,
 baseline_at BIGINT NOT NULL,
 PRIMARY KEY(subscription_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rss_item (
 subscription_id VARCHAR(36) NOT NULL,
 revision INT NOT NULL,
 gid BIGINT NOT NULL,
 token VARCHAR(255) NOT NULL,
 url VARCHAR(500) NOT NULL,
 title VARCHAR(1000) NOT NULL,
 published_at BIGINT NOT NULL,
 discovered_at BIGINT NOT NULL,
 eligible BOOLEAN NOT NULL,
 PRIMARY KEY(subscription_id, revision, gid),
 INDEX idx_rss_item_gid(gid),
 INDEX idx_rss_item_time(subscription_id, discovered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rss_download_task (
 gid BIGINT NOT NULL PRIMARY KEY,
 url VARCHAR(500) NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
 batch_id VARCHAR(36) NULL,
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL,
 last_error VARCHAR(2000) NULL,
 INDEX idx_rss_task_due(status, next_attempt_at),
 INDEX idx_rss_task_batch(batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rss_batch (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 workflow_id VARCHAR(150) NOT NULL UNIQUE,
 run_id VARCHAR(100) NULL,
 status VARCHAR(24) NOT NULL,
 created_at BIGINT NOT NULL,
 last_error VARCHAR(2000) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rss_poll_run (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 subscription_id VARCHAR(36) NOT NULL,
 revision INT NOT NULL,
 started_at BIGINT NOT NULL,
 finished_at BIGINT NULL,
 status VARCHAR(32) NOT NULL,
 discovered_count INT NOT NULL DEFAULT 0,
 pages INT NOT NULL DEFAULT 0,
 message VARCHAR(2000) NULL,
 INDEX idx_rss_run(subscription_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rss_dispatch_lock (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO rss_dispatch_lock(id) VALUES(1);
