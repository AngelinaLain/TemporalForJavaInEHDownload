package com.checker.dto;

import lombok.Data;

/** Query-only fields; raw tags are JSON arrays so tag values may safely contain commas. */
@Data
public class GallerySearchRequest {
    private int page = 1;
    private int size = 20;
    private String status;
    private String gid;
    private String keyword;
    private String keywordMode = "contains";
    private String tag;
    private String tags;
    private String excludeTags;
    private String tagMode = "all";
    private String dedupe = "preferred";
    private String sortBy = "crawledAt";
    private String sortOrder = "desc";
    private String collectionScope = "all";
    private Long collectionId;
    private Double minRating;
    private Double maxRating;
    private Integer minPages;
    private Integer maxPages;
    private Double minSizeMb;
    private Double maxSizeMb;
    private String from;
    private String to;
    private String searchQuery;
    private String komga = "all";
    private String ratingBucket;
    private String pageBucket;
    private String sizeBucket;
    private String quality;
}
