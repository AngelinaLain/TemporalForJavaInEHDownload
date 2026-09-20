package com.checker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class GalleryCollectionRequest {
    @NotBlank(message = "合集名称不能为空")
    @Size(max = 200, message = "合集名称不能超过 200 个字符")
    private String name;

    @Size(max = 1000, message = "合集说明不能超过 1000 个字符")
    private String description;
}
