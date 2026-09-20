package com.checker.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class GalleryCollectionSummary {
    private Long id;
    private String name;
    private String description;
    private Long itemCount;
    private Date updatedAt;
}
