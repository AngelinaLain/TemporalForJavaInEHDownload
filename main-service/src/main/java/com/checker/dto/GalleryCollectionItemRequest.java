package com.checker.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class GalleryCollectionItemRequest {
    @NotEmpty(message = "至少选择一个画廊")
    private List<Long> gids;
    private String source = "MANUAL";
}
