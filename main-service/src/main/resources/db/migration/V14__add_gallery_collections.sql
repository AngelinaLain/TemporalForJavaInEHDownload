CREATE TABLE IF NOT EXISTS `eh_gallery_collections` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(200) NOT NULL,
  `description` VARCHAR(1000) NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_gallery_collection_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `eh_gallery_collection_items` (
  `collection_id` BIGINT NOT NULL,
  `gid` BIGINT NOT NULL,
  `sort_order` INT NOT NULL DEFAULT 0,
  `added_source` VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`collection_id`, `gid`),
  UNIQUE KEY `uk_gallery_collection_item_gid` (`gid`),
  KEY `idx_gallery_collection_order` (`collection_id`, `sort_order`, `gid`),
  CONSTRAINT `fk_gallery_collection_item_collection`
    FOREIGN KEY (`collection_id`) REFERENCES `eh_gallery_collections` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_gallery_collection_item_gallery`
    FOREIGN KEY (`gid`) REFERENCES `eh_galleries` (`gid`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
