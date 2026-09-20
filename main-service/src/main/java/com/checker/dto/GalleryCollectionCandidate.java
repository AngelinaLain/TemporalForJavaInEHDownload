package com.checker.dto;

import lombok.Builder;
import lombok.Data;

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
