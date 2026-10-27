package com.checker.dto;

import com.checker.entity.EhGalleriesEntity;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 承载单个搜索页抓到的画廊和下一页地址，让主工作流逐页接收结果。
 * 每次只返回一页，避免一次传输全部画廊时超过 Temporal 的消息大小限制。
 */
@Data
@NoArgsConstructor
public class GalleryScrapePage implements Serializable {
    private List<EhGalleriesEntity> galleries = new ArrayList<>();
    private String nextUrl;
    private boolean hasData;
    private boolean hasNextPage;
    private String stopReason;
}
