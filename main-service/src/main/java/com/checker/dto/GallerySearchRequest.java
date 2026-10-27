package com.checker.dto;

import lombok.Data;

/**
 * 承接画廊列表的分页、排序和组合检索条件，不负责执行查询。
 * 标签列表使用 JSON 数组传递，避免把标签名称自身包含的逗号误认为分隔符。
 */
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
