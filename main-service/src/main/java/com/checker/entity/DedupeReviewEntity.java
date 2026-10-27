package com.checker.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 保存两条疑似重复画廊的比较证据和人工结论，记录最终保留哪个版本。
 */
@Data
@TableName("eh_dedupe_reviews")
public class DedupeReviewEntity implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String candidateKey;
    private Long leftGid;
    private Long rightGid;
    private Integer matchScore;
    private String matchReason;
    private Integer visualSimilarity;
    private Integer visualMatchedPages;
    private Integer visualSampleCoverage;
    private Integer visualOrderConsistency;
    private Long visualRecommendedGid;
    private Integer visualQualityDelta;
    private String visualReason;
    private Integer visualAlgorithmVersion;
    private Long recommendedGid;
    private String decision;
    private Long preferredGid;
    private String reviewedBy;
    private Date reviewedAt;
    @TableField(fill = FieldFill.INSERT)
    private Date createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updatedAt;
}
