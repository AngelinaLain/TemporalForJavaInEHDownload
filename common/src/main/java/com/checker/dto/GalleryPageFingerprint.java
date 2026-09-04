package com.checker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** A compact, versioned perceptual fingerprint for one sampled gallery page. */
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
