package com.checker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 返回两本画廊的页面匹配分数、覆盖率和推荐版本，并附上可供人工理解的判断理由。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GalleryVisualMatch implements Serializable {
    private Long leftGid;
    private Long rightGid;
    private Integer similarity;
    private Integer matchedPages;
    private Integer leftSamples;
    private Integer rightSamples;
    private Integer sampleCoverage;
    private Integer orderConsistency;
    private Long recommendedGid;
    private Integer qualityDelta;
    private String reason;
    private Integer algorithmVersion;
}
