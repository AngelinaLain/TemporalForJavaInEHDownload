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
            "(candidate_key, left_gid, right_gid, match_score, match_reason, recommended_gid, decision) " +
            "VALUES (#{candidateKey}, #{leftGid}, #{rightGid}, #{score}, #{reason}, #{recommendedGid}, 'PENDING') " +
            "ON DUPLICATE KEY UPDATE candidate_key = VALUES(candidate_key)")
    int insertPending(@Param("candidateKey") String candidateKey,
                      @Param("leftGid") Long leftGid,
                      @Param("rightGid") Long rightGid,
                      @Param("score") int score,
                      @Param("reason") String reason,
                      @Param("recommendedGid") Long recommendedGid);

    @Select("SELECT * FROM eh_dedupe_reviews WHERE id = #{id} FOR UPDATE")
    DedupeReviewEntity selectByIdForUpdate(@Param("id") Long id);
}
