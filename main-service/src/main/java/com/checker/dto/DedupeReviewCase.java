package com.checker.dto;

import com.checker.entity.EhGalleriesEntity;
import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 把待审核记录、左右两本画廊和比较证据一起返回给前端，方便并排核对。
 */
@Data
@Builder
public class DedupeReviewCase {
    private Long id;
    private String candidateKey;
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
    private Date createdAt;
    private EhGalleriesEntity left;
    private EhGalleriesEntity right;
}
