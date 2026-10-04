CREATE TABLE IF NOT EXISTS `ai_provider_configs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `scope` VARCHAR(16) NOT NULL DEFAULT 'REMOTE',
  `protocol` VARCHAR(32) NOT NULL DEFAULT 'OPENAI_COMPATIBLE',
  `base_url` VARCHAR(1000) NOT NULL,
  `api_key_encrypted` TEXT NULL,
  `default_model` VARCHAR(255) NULL,
  `allow_text_metadata` BOOLEAN NOT NULL DEFAULT TRUE,
  `allow_visual_input` BOOLEAN NOT NULL DEFAULT FALSE,
  `request_timeout_seconds` INT NOT NULL DEFAULT 120,
  `max_concurrency` INT NOT NULL DEFAULT 2,
  `enabled` BOOLEAN NOT NULL DEFAULT TRUE,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_provider_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_prompt_versions` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `use_case` VARCHAR(64) NOT NULL,
  `version` INT NOT NULL,
  `prompt` TEXT NOT NULL,
  `is_default` BOOLEAN NOT NULL DEFAULT FALSE,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_prompt_use_case_version` (`use_case`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_use_case_configs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `use_case` VARCHAR(64) NOT NULL,
  `provider_id` BIGINT NULL,
  `model` VARCHAR(255) NULL,
  `analysis_mode` VARCHAR(32) NOT NULL DEFAULT 'EMBEDDING_ONLY',
  `allow_image_transmission` BOOLEAN NOT NULL DEFAULT FALSE,
  `include_metadata` BOOLEAN NOT NULL DEFAULT FALSE,
  `sample_page_count` INT NOT NULL DEFAULT 4,
  `ambiguous_min_similarity` DECIMAL(6,5) NOT NULL DEFAULT 0.86000,
  `high_similarity` DECIMAL(6,5) NOT NULL DEFAULT 0.94000,
  `prompt_version_id` BIGINT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_use_case` (`use_case`),
  CONSTRAINT `fk_ai_use_case_provider` FOREIGN KEY (`provider_id`) REFERENCES `ai_provider_configs` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_ai_use_case_prompt` FOREIGN KEY (`prompt_version_id`) REFERENCES `ai_prompt_versions` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `eh_gallery_page_embeddings` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `gid` BIGINT NOT NULL,
  `page_index` INT NOT NULL,
  `crop_type` VARCHAR(16) NOT NULL DEFAULT 'FULL',
  `model_name` VARCHAR(100) NOT NULL,
  `model_version` VARCHAR(100) NOT NULL,
  `dimensions` INT NOT NULL,
  `embedding` MEDIUMBLOB NOT NULL,
  `source_fingerprint` CHAR(64) NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_gallery_page_embedding` (`gid`, `page_index`, `crop_type`, `model_version`),
  KEY `idx_gallery_embedding_gid_model` (`gid`, `model_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_visual_review_jobs` (
  `id` CHAR(36) NOT NULL,
  `workflow_id` VARCHAR(255) NULL,
  `left_gid` BIGINT NOT NULL,
  `right_gid` BIGINT NOT NULL,
  `status` VARCHAR(24) NOT NULL,
  `analysis_mode` VARCHAR(32) NOT NULL,
  `provider_id` BIGINT NULL,
  `model` VARCHAR(255) NULL,
  `prompt_version_id` BIGINT NULL,
  `allow_image_transmission` BOOLEAN NOT NULL DEFAULT FALSE,
  `include_metadata` BOOLEAN NOT NULL DEFAULT FALSE,
  `sample_page_count` INT NOT NULL DEFAULT 4,
  `ambiguous_min_similarity` DECIMAL(6,5) NOT NULL DEFAULT 0.86000,
  `high_similarity` DECIMAL(6,5) NOT NULL DEFAULT 0.94000,
  `last_error` VARCHAR(1000) NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `started_at` TIMESTAMP NULL,
  `finished_at` TIMESTAMP NULL,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_visual_job_pair` (`left_gid`, `right_gid`, `created_at`),
  KEY `idx_ai_visual_job_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_visual_review_results` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `job_id` CHAR(36) NOT NULL,
  `decision` VARCHAR(32) NOT NULL,
  `confidence` DECIMAL(6,5) NOT NULL,
  `embedding_similarity` DECIMAL(6,5) NULL,
  `perceptual_hash_similarity` DECIMAL(6,5) NULL,
  `matched_pages` INT NOT NULL DEFAULT 0,
  `compared_pages` INT NOT NULL DEFAULT 0,
  `page_order_consistency` DECIMAL(6,5) NULL,
  `reason` VARCHAR(2000) NULL,
  `requires_human_review` BOOLEAN NOT NULL DEFAULT TRUE,
  `llm_used` BOOLEAN NOT NULL DEFAULT FALSE,
  `images_transmitted` BOOLEAN NOT NULL DEFAULT FALSE,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_visual_result_job` (`job_id`),
  CONSTRAINT `fk_ai_visual_result_job` FOREIGN KEY (`job_id`) REFERENCES `ai_visual_review_jobs` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
