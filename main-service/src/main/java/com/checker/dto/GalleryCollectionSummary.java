package com.checker.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 返回合集列表所需的简要信息，包括名称、说明、画廊数量和更新时间。
 */
@Data
@Builder
public class GalleryCollectionSummary {
    private Long id;
    private String name;
    private String description;
    private Long itemCount;
    private Date updatedAt;
}
