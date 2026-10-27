package com.checker.temporalServices.activities;

import com.checker.dto.ArchiveDownloadInfo;
import com.checker.dto.SearchOptions;
import com.checker.dto.GalleryPageFingerprint;
import com.checker.dto.GalleryScrapePage;
import com.checker.entity.EhGalleriesEntity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;
import java.util.Map;

/**
 * 定义爬虫节点提供的搜索、画廊详情、下载地址与预览指纹采集任务。
 */
@ActivityInterface
public interface ScraperActivity {

    /**
     * 根据搜索条件抓取画廊列表，供兼容旧工作流的调用使用。
     */
    @ActivityMethod
    List<EhGalleriesEntity> scrapeGalleries(SearchOptions searchOptions);

    /**
     * 抓取一页搜索结果。新工作流逐页调用，避免将整批画廊序列化为单个超过 gRPC 上限的结果。
     * {@code currentUrl} 为空时从搜索条件构建首页 URL。
     */
    @ActivityMethod
    GalleryScrapePage scrapeGalleryPage(SearchOptions searchOptions, String currentUrl, int pageNo);

    /**
     * 根据包含 GID 与 token 的画廊链接获取画廊元数据。
     */
    @ActivityMethod
    List<EhGalleriesEntity> scrapeGalleriesByUrls(List<String> galleryUrls);

    /**
     * 访问 archiver.php 提取最终下载直链
     */
    @ActivityMethod
    ArchiveDownloadInfo extractDownloadUrl(Long gid, String token);

    /** 获取画廊预览缩略图并计算少量视觉指纹，供下载前灰区判定使用。 */
    @ActivityMethod
    Map<Long, List<GalleryPageFingerprint>> analyzeGalleryPreviews(List<EhGalleriesEntity> galleries);
}
