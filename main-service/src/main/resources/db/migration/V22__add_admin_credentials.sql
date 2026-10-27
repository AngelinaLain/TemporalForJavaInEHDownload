-- The configured ADMIN_PASSWORD_HASH bootstraps only accounts without an override.
-- Password changes persist here and survive container restarts or environment reloads.
CREATE TABLE IF NOT EXISTS eh_admin_credentials (
    username VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    credential_version BIGINT NOT NULL DEFAULT 0,
    changed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
