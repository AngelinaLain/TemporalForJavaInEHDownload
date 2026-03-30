package com.checker.temporalServices.activities;

import com.checker.dto.SearchOptions;
import com.checker.entity.EhGalleriesEntity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

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
    String extractDownloadUrl(Long gid, String token);
}
