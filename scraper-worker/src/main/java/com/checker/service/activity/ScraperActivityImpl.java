package com.checker.service.activity;

import com.checker.dto.ArchiveDownloadInfo;
import com.checker.dto.GalleryPageFingerprint;
import com.checker.dto.GalleryScrapePage;
import com.checker.dto.SearchOptions;
import com.checker.entity.EhGalleriesEntity;
import com.checker.manager.source.ScraperManager;
import com.checker.shared.Constants;
import com.checker.temporal.activity.ScraperActivity;
import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Temporal 活动入口负责进度心跳，源站访问与页面解析由 Manager 完成。 */
@Component
@ActivityImpl(taskQueues = Constants.SCRAPER_TASK_QUEUE)
public class ScraperActivityImpl implements ScraperActivity {
    private final ScraperManager scraper;

    public ScraperActivityImpl(ScraperManager scraper) {
        this.scraper = scraper;
    }

    @Override
    public List<EhGalleriesEntity> scrapeGalleries(SearchOptions searchOptions) {
        return scraper.scrapeGalleries(searchOptions, ScraperActivityImpl::heartbeat);
    }

    @Override
    public GalleryScrapePage scrapeGalleryPage(SearchOptions searchOptions, String currentUrl, int pageNo) {
        return scraper.scrapeGalleryPage(searchOptions, currentUrl, pageNo, ScraperActivityImpl::heartbeat);
    }

    @Override
    public List<EhGalleriesEntity> scrapeGalleriesByUrls(List<String> galleryUrls) {
        return scraper.scrapeGalleriesByUrls(galleryUrls);
    }

    @Override
    public ArchiveDownloadInfo extractDownloadUrl(Long gid, String token) {
        return scraper.extractDownloadUrl(gid, token, ScraperActivityImpl::heartbeat);
    }

    @Override
    public Map<Long, List<GalleryPageFingerprint>> analyzeGalleryPreviews(List<EhGalleriesEntity> galleries) {
        return scraper.analyzeGalleryPreviews(galleries, ScraperActivityImpl::heartbeat);
    }

    private static void heartbeat(Object progress) {
        Activity.getExecutionContext().heartbeat(progress);
    }
}
