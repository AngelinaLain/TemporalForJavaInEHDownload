package com.checker.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 返回建议加入合集的画廊及推荐理由，包含标题、封面和元数据的匹配分数。
 */
@Data
@Builder
public class GalleryCollectionCandidate {
    private Long gid;
    private String title;
    private String originalTitle;
    private String galleryUrl;
    private Integer pageCount;
    private Double rating;
    private Long collectionId;
    private String collectionName;
    private Integer score;
    private Integer titleSimilarity;
    private Integer coverSimilarity;
    private Integer metadataSimilarity;
    private String reason;
}
