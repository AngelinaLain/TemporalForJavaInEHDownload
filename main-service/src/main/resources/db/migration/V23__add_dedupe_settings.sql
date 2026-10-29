-- 保存整个系统共用的去重策略。未保存过时由程序读取默认值，查询设置不会写入数据库。
CREATE TABLE IF NOT EXISTS eh_dedupe_settings (
    id TINYINT UNSIGNED NOT NULL,
    review_score TINYINT UNSIGNED NOT NULL,
    auto_match_score TINYINT UNSIGNED NOT NULL,
    title_similarity_percent TINYINT UNSIGNED NOT NULL,
    visual_page_distance TINYINT UNSIGNED NOT NULL,
    visual_min_matched_pages TINYINT UNSIGNED NOT NULL,
    visual_min_coverage TINYINT UNSIGNED NOT NULL,
    visual_quality_delta TINYINT UNSIGNED NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
