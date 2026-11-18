package com.checker.web.catalog;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.checker.model.dto.catalog.GalleryAnalyticsRequest;
import com.checker.model.dto.catalog.GallerySearchRequest;
import com.checker.service.catalog.EhTagTranslationService;
import com.checker.service.catalog.GalleryAnalyticsService;
import com.checker.service.catalog.GalleryDashboardService;
import com.checker.service.catalog.GalleryQueryService;
import com.checker.service.catalog.TagNetworkService;
import com.checker.shared.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 为数据概览、标签图谱和画廊检索提供查询接口，只做参数转发和结果包装。
 */
@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "仪表盘", description = "图库统计、检索、进度与标签信息")
@PreAuthorize("hasRole('ADMIN')")
public class DashboardController {

    private final GalleryDashboardService dashboardService;
    private final GalleryAnalyticsService galleryAnalyticsService;
    private final GalleryQueryService galleryQueryService;
    private final TagNetworkService tagNetworkService;
    private final EhTagTranslationService tagTranslationService;

    public DashboardController(GalleryDashboardService dashboardService,
                               GalleryAnalyticsService galleryAnalyticsService,
                               GalleryQueryService galleryQueryService,
                               TagNetworkService tagNetworkService,
                               EhTagTranslationService tagTranslationService) {
        this.dashboardService = dashboardService;
        this.galleryAnalyticsService = galleryAnalyticsService;
        this.galleryQueryService = galleryQueryService;
        this.tagNetworkService = tagNetworkService;
        this.tagTranslationService = tagTranslationService;
    }

    /**
     * 返回画廊状态及容量等基础汇总，不逐条加载全部画廊。
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        return Result.success(dashboardService.overview());
    }

    /**
     * 按日期、状态和合集等统一范围获取统计数据，筛选条件不合法时返回明确提示。
     */
    @GetMapping("/analytics")
    public Result<Map<String, Object>> getAnalytics(@ModelAttribute GalleryAnalyticsRequest request) {
        try {
            return Result.success(galleryAnalyticsService.analyze(request));
        } catch (IllegalArgumentException failure) {
            return Result.error(400, failure.getMessage());
        }
    }

    /**
     * 返回高频标签关系，或按固定快照分页读取全部标签目录，避免加载时排序变化造成漏项。
     */
    @GetMapping("/tag-network")
    public Result<Map<String, Object>> getTagNetwork(
            @RequestParam(defaultValue = "36") int limit,
            @RequestParam(defaultValue = "2") int minWeight,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "500") int pageSize,
            @RequestParam(required = false) String snapshotId) {
        try {
            return Result.success(tagNetworkService.network(limit, minWeight, keyword, offset, pageSize, snapshotId));
        } catch (IllegalArgumentException failure) {
            return Result.error(400, failure.getMessage());
        }
    }

    /**
     * 仅查询选定标签的直接共现关系，并返回真实关联总数和是否截断的标记。
     */
    @GetMapping("/tag-network/neighbors")
    public Result<Map<String, Object>> getTagNeighbors(
            @RequestParam String tag,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "2") int minWeight,
            @RequestParam(defaultValue = "100") int limit) {
        try {
            return Result.success(tagNetworkService.neighbors(tag, keyword, minWeight, limit));
        } catch (IllegalArgumentException failure) {
            return Result.error(400, failure.getMessage());
        }
    }

    /**
     * 返回下载状态分布，供概览图表使用。
     */
    @GetMapping("/status-distribution")
    public Result<List<Map<String, Object>>> getStatusDistribution() {
        return Result.success(dashboardService.statusDistribution());
    }

    /**
     * 返回当前下载中的画廊及其下载进度，供概览实时展示。
     */
    @GetMapping("/download-progress")
    public Result<List<Map<String, Object>>> getDownloadProgress() {
        return Result.success(dashboardService.downloadProgress());
    }

    /**
     * 查询数据库连接及容量等信息，失败时返回可识别的服务状态。
     */
    @GetMapping("/db-status")
    public Result<Map<String, Object>> getDbStatus() {
        return Result.success(dashboardService.databaseStatus());
    }

    /**
     * 返回画廊文件体积的区间分布，用于容量图表。
     */
    @GetMapping("/file-size-distribution")
    public Result<Map<String, Object>> getFileSizeDistribution() {
        return Result.success(dashboardService.fileSizeDistribution());
    }

    /**
     * 按抓取时间统计画廊数量，供历史趋势图展示。
     */
    @GetMapping("/crawl-timeline")
    public Result<Map<String, Object>> getCrawlTimeline() {
        return Result.success(dashboardService.crawlTimeline());
    }

    /**
     * 汇总标签分类数量，供概览查看内容组成。
     */
    @GetMapping("/tag-stats")
    public Result<List<Map<String, Object>>> getTagStats() {
        return Result.success(dashboardService.tagStatistics());
    }

    /**
     * 执行画廊列表的组合检索与分页，只返回当前页需要的轻量字段。
     */
    @GetMapping("/galleries")
    public Result<IPage<Map<String, Object>>> getGalleries(@ModelAttribute GallerySearchRequest request) {
        try {
            return Result.success(galleryQueryService.search(request));
        } catch (IllegalArgumentException failure) {
            return Result.error(400, failure.getMessage());
        }
    }

    /**
     * 为标题或标签输入返回数量受限的检索建议，帮助用户快速选择条件。
     */
    @GetMapping("/suggestions")
    public Result<List<Map<String, String>>> getSuggestions(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "all") String type) {
        try {
            return Result.success(dashboardService.suggestions(q, limit, type));
        } catch (IllegalArgumentException failure) {
            return Result.error(400, failure.getMessage());
        }
    }

    /**
     * 返回可供前端批量使用的标签翻译字典。
     */
    @GetMapping("/tag-translations")
    public Result<Map<String, String>> getTagTranslations() {
        return Result.success(tagTranslationService.getTranslationMap());
    }

    /**
     * 主动刷新标签翻译数据，让后续查询使用最新字典。
     */
    @PostMapping("/tag-translations/refresh")
    public Result<String> refreshTagTranslations() {
        tagTranslationService.refreshCache();
        return Result.success("翻译缓存刷新成功");
    }

    /**
     * 查询单个标签的翻译和详细信息，供页面按需展示。
     */
    @GetMapping("/tag-detail")
    public Result<Map<String, String>> getTagDetail(@RequestParam String tag) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("tag", tag);
        detail.put("name", tagTranslationService.translate(tag));
        detail.put("intro", tagTranslationService.getDescription(tag));
        return Result.success(detail);
    }
}
