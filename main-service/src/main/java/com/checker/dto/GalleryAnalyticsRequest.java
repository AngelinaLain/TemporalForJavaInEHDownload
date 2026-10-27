package com.checker.dto;

import lombok.Data;

/**
 * 承接数据概览的日期、状态、合集和时间粒度筛选，让图表使用同一统计范围。
 */
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
