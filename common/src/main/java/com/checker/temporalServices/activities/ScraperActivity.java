package com.checker.temporalServices.activities;

import com.checker.dto.ArchiveDownloadInfo;
import com.checker.dto.SearchOptions;
import com.checker.dto.GalleryPageFingerprint;
import com.checker.entity.EhGalleriesEntity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;
import java.util.Map;

/**
 * 爬虫域 Activity：负责从 EHentai 检索/抓取数据
 */
@ActivityInterface
public interface ScraperActivity {

    /**
     * 根据搜索条件抓取画廊列表（最多 10 页）
     */
    @ActivityMethod
    List<EhGalleriesEntity> scrapeGalleries(SearchOptions searchOptions);

    /**
     * 访问 archiver.php 提取最终下载直链
     */
    @ActivityMethod
    ArchiveDownloadInfo extractDownloadUrl(Long gid, String token);

    /** 获取画廊预览缩略图并计算少量视觉指纹，供下载前灰区判定使用。 */
    @ActivityMethod
    Map<Long, List<GalleryPageFingerprint>> analyzeGalleryPreviews(List<EhGalleriesEntity> galleries);
}
