package com.checker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.checker.entity.DedupeReviewEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DedupeReviewMapper extends BaseMapper<DedupeReviewEntity> {

    @Insert("INSERT INTO eh_dedupe_reviews " +
            "(candidate_key, left_gid, right_gid, match_score, match_reason, " +
            "visual_similarity, visual_matched_pages, visual_sample_coverage, visual_order_consistency, " +
            "visual_recommended_gid, visual_quality_delta, visual_reason, visual_algorithm_version, " +
            "recommended_gid, decision) " +
            "VALUES (#{candidateKey}, #{leftGid}, #{rightGid}, #{score}, #{reason}, " +
            "#{visualSimilarity}, #{visualMatchedPages}, #{visualCoverage}, #{visualOrderConsistency}, " +
            "#{visualRecommendedGid}, #{visualQualityDelta}, #{visualReason}, #{visualAlgorithmVersion}, " +
            "#{recommendedGid}, 'PENDING') " +
            "ON DUPLICATE KEY UPDATE candidate_key = VALUES(candidate_key)")
    int insertPending(@Param("candidateKey") String candidateKey,
                      @Param("leftGid") Long leftGid,
                      @Param("rightGid") Long rightGid,
                      @Param("score") int score,
                      @Param("reason") String reason,
                      @Param("visualSimilarity") Integer visualSimilarity,
                      @Param("visualMatchedPages") Integer visualMatchedPages,
                      @Param("visualCoverage") Integer visualCoverage,
                      @Param("visualOrderConsistency") Integer visualOrderConsistency,
                      @Param("visualRecommendedGid") Long visualRecommendedGid,
                      @Param("visualQualityDelta") Integer visualQualityDelta,
                      @Param("visualReason") String visualReason,
                      @Param("visualAlgorithmVersion") Integer visualAlgorithmVersion,
                      @Param("recommendedGid") Long recommendedGid);

    @Select("SELECT * FROM eh_dedupe_reviews WHERE id = #{id} FOR UPDATE")
    DedupeReviewEntity selectByIdForUpdate(@Param("id") Long id);
}
