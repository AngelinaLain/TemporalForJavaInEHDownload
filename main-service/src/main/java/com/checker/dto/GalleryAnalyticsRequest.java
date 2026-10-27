package com.checker.dto;

import lombok.Data;

@Data
public class GalleryAnalyticsRequest {
    private int days = 30;
    private String from;
    private String to;
    private String status;
    private String dedupe = "all";
    private String collectionScope = "all";
    private Long collectionId;
    private String grain = "day";
}
