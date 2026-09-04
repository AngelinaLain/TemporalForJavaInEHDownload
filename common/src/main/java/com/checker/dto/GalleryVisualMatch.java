package com.checker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Explainable gallery-level result produced by monotonic page-hash alignment. */
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
