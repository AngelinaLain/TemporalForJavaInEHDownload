package com.checker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 在服务间传递单张抽样页的感知哈希、画质和算法版本，用于比较画廊是否重复。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GalleryPageFingerprint implements Serializable {
    private Long gid;
    private Integer pageIndex;
    private String pageName;
    private String source;
    private String perceptualHash;
    private String centerHash;
    private Integer quality;
    private Integer width;
    private Integer height;
    private Integer algorithmVersion;
}
