package com.checker.mapper;

import com.checker.common.DedupeSettings;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 读写唯一一份全局去重参数，让多个后端实例使用同一套判断标准。 */
@Mapper
public interface DedupeSettingsMapper {
    /** 按固定顺序装配不可变参数快照；尚未保存时返回空，由服务提供默认值。 */
    @Select("SELECT review_score, auto_match_score, title_similarity_percent, visual_page_distance, "
            + "visual_min_matched_pages, visual_min_coverage, visual_quality_delta FROM eh_dedupe_settings WHERE id = 1")
    @ConstructorArgs({
            @Arg(column = "review_score", javaType = int.class),
            @Arg(column = "auto_match_score", javaType = int.class),
            @Arg(column = "title_similarity_percent", javaType = int.class),
            @Arg(column = "visual_page_distance", javaType = int.class),
            @Arg(column = "visual_min_matched_pages", javaType = int.class),
            @Arg(column = "visual_min_coverage", javaType = int.class),
            @Arg(column = "visual_quality_delta", javaType = int.class)
    })
    DedupeSettings current();

    /** 一条语句整体替换设置，避免调用方读到一半新参数、一半旧参数。 */
    @Insert("INSERT INTO eh_dedupe_settings (id, review_score, auto_match_score, title_similarity_percent, "
            + "visual_page_distance, visual_min_matched_pages, visual_min_coverage, visual_quality_delta) "
            + "VALUES (1, #{settings.reviewScore}, #{settings.autoMatchScore}, #{settings.titleSimilarityPercent}, "
            + "#{settings.visualPageDistance}, #{settings.visualMinMatchedPages}, #{settings.visualMinCoverage}, "
            + "#{settings.visualQualityDelta}) ON DUPLICATE KEY UPDATE "
            + "review_score = VALUES(review_score), auto_match_score = VALUES(auto_match_score), "
            + "title_similarity_percent = VALUES(title_similarity_percent), visual_page_distance = VALUES(visual_page_distance), "
            + "visual_min_matched_pages = VALUES(visual_min_matched_pages), visual_min_coverage = VALUES(visual_min_coverage), "
            + "visual_quality_delta = VALUES(visual_quality_delta)")
    void save(@Param("settings") DedupeSettings settings);
}
