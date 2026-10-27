package com.checker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 接收创建或修改本地画廊合集时填写的名称和说明。
 */
@Data
public class GalleryCollectionRequest {
    @NotBlank(message = "合集名称不能为空")
    @Size(max = 200, message = "合集名称不能超过 200 个字符")
    private String name;

    @Size(max = 1000, message = "合集说明不能超过 1000 个字符")
    private String description;
}
