package com.checker.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 接收一批待加入合集的画廊 GID，以及这次归组的来源。
 */
@Data
public class GalleryCollectionItemRequest {
    @NotEmpty(message = "至少选择一个画廊")
    private List<Long> gids;
    private String source = "MANUAL";
}
