package com.checker.dto;

import com.checker.entity.EhGalleriesEntity;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个 EH 搜索页的抓取结果。
 *
 * <p>不要把完整搜索结果作为一个 Activity 返回值。Temporal 默认的 gRPC 消息上限为
 * 4 MiB，大搜索会使整个实体列表超过该限制。此 DTO 将跨服务传输限制为一页的数据。</p>
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
