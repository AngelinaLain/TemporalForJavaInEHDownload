package com.checker.controllers;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.checker.common.Result;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.service.EhGalleriesService;
import com.checker.service.EhTagTranslationService;
import com.checker.dto.GalleryAnalyticsRequest;
import com.checker.dto.GallerySearchRequest;
import com.checker.service.GalleryAnalyticsService;
import com.checker.service.GalleryQueryService;
import com.checker.service.TagNetworkService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 为数据概览、标签图谱和画廊检索提供查询接口，把聚合结果整理为前端使用的格式。
 */
@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "仪表盘", description = "图库统计、检索、进度与标签信息")
@PreAuthorize("hasRole('ADMIN')")
public class DashboardController {

    private final EhGalleriesService galleriesService;
    private final EhTagTranslationService tagTranslationService;
    private final EhGalleriesMapper galleriesMapper;
    private final JdbcTemplate jdbcTemplate;
    private final GalleryQueryService galleryQueryService;
    private final GalleryAnalyticsService galleryAnalyticsService;
    private final TagNetworkService tagNetworkService;

    public DashboardController(EhGalleriesService galleriesService,
                               EhTagTranslationService tagTranslationService,
                               EhGalleriesMapper galleriesMapper,
                               JdbcTemplate jdbcTemplate,
                               GalleryQueryService galleryQueryService,
                               GalleryAnalyticsService galleryAnalyticsService,
                               TagNetworkService tagNetworkService) {
        this.galleriesService = galleriesService;
        this.tagTranslationService = tagTranslationService;
        this.galleriesMapper = galleriesMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.galleryQueryService = galleryQueryService;
        this.galleryAnalyticsService = galleryAnalyticsService;
        this.tagNetworkService = tagNetworkService;
    }

    /**
     * 返回画廊状态及容量等基础汇总，不逐条加载全部画廊。
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        Map<String, Object> overview = galleriesMapper.getDashboardOverview();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", toLong(overview.get("total")));
        stats.put("downloaded", toLong(overview.get("downloaded")));
        stats.put("imported", toLong(overview.get("imported")));
        stats.put("failed", toLong(overview.get("failed")));
        stats.put("pending", toLong(overview.get("pending")));
        stats.put("totalSizeGb", Math.round(toDouble(overview.get("total_size_mb")) / 1024 * 100.0) / 100.0);
        return Result.success(stats);
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
     * 把数据库聚合的下载状态数量整理为状态分布数据。
     */
    @GetMapping("/status-distribution")
    public Result<List<Map<String, Object>>> getStatusDistribution() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : galleriesMapper.countByDownloadStatus()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", String.valueOf(row.get("status")));
            item.put("value", toLong(row.get("cnt")));
            result.add(item);
        }
        return Result.success(result);
    }

    private long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    /**
     * 返回当前下载中的画廊及其下载进度，供概览实时展示。
     */
    @GetMapping("/download-progress")
    public Result<List<Map<String, Object>>> getDownloadProgress() {
        QueryWrapper<EhGalleriesEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("download_status", "DOWNLOADING").or().eq("download_status", "下载中");
        List<EhGalleriesEntity> downloading = galleriesService.list(wrapper);

        List<Map<String, Object>> result = new ArrayList<>();
        for (EhGalleriesEntity gallery : downloading) {
            long downloadedBytes = gallery.getDownloadedBytes() != null ? gallery.getDownloadedBytes() : 0L;
            double sizeMb = gallery.getFileSizeMb() != null ? gallery.getFileSizeMb() : 0.0;
            long totalBytes = (long) (sizeMb * 1024 * 1024);
            double percent = totalBytes > 0 ? Math.min(100.0, downloadedBytes * 100.0 / totalBytes) : 0.0;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("gid", gallery.getGid());
            item.put("title", gallery.getTitle());
            item.put("downloadedBytes", downloadedBytes);
            item.put("totalBytes", totalBytes);
            item.put("sizeMb", sizeMb);
            item.put("percent", Math.round(percent * 10) / 10.0);
            result.add(item);
        }
        return Result.success(result);
    }

    /**
     * 查询数据库连接及容量等信息，失败时返回可识别的服务状态。
     */
    @GetMapping("/db-status")
    public Result<Map<String, Object>> getDbStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        try {
            Integer probe = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            status.put("connected", probe != null && probe == 1);
        } catch (Exception e) {
            status.put("connected", false);
            status.put("error", e.getMessage());
        }
        try {
            Map<String, Object> overview = galleriesMapper.getDashboardOverview();
            status.put("total", toLong(overview.get("total")));
            status.put("totalSizeGb", Math.round(toDouble(overview.get("total_size_mb")) / 1024 * 100.0) / 100.0);
        } catch (Exception ignored) {
            status.put("total", 0L);
            status.put("totalSizeGb", 0.0);
        }
        return Result.success(status);
    }

    /**
     * 返回画廊文件体积的区间分布，用于容量图表。
     */
    @GetMapping("/file-size-distribution")
    public Result<Map<String, Object>> getFileSizeDistribution() {
        Map<String, Object> buckets = galleriesMapper.getFileSizeBuckets();
        String[] labels = {"<50MB", "50-100MB", "100-200MB", "200-500MB", "500MB-1GB", ">1GB"};
        long[] counts = {
                toLong(buckets.get("lt_50")),
                toLong(buckets.get("from_50_to_100")),
                toLong(buckets.get("from_100_to_200")),
                toLong(buckets.get("from_200_to_500")),
                toLong(buckets.get("from_500_to_1024")),
                toLong(buckets.get("ge_1024"))
        };

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("labels", labels);
        result.put("data", counts);
        return Result.success(result);
    }
    /**
     * 按抓取时间统计画廊数量，供历史趋势图展示。
     */
    @GetMapping("/crawl-timeline")
    public Result<Map<String, Object>> getCrawlTimeline() {
        QueryWrapper<EhGalleriesEntity> wrapper = new QueryWrapper<>();
        wrapper.isNotNull("crawled_at")
                .select("DATE(crawled_at) as crawl_date", "COUNT(*) as cnt")
                .groupBy("DATE(crawled_at)")
                .orderByAsc("DATE(crawled_at)")
                .last("LIMIT 60");

        List<Map<String, Object>> rows = galleriesService.listMaps(wrapper);

        List<String> dates = new ArrayList<>();
        List<Long> counts = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            dates.add(String.valueOf(row.get("crawl_date")));
            counts.add(((Number) row.get("cnt")).longValue());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dates", dates);
        result.put("counts", counts);
        return Result.success(result);
    }

    /**
     * 汇总标签分类数量，供概览查看内容组成。
     */
    @GetMapping("/tag-stats")
    public Result<List<Map<String, Object>>> getTagStats() {
        List<Map<String, Object>> rows = galleriesMapper.countTagNamespaces();
        List<Map<String, Object>> result = rows.stream().map(row -> {
            String ns = String.valueOf(row.get("namespace"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", ns);
            item.put("nameCn", tagTranslationService.translateNamespace(ns));
            item.put("value", ((Number) row.get("cnt")).longValue());
            return item;
        }).collect(Collectors.toList());
        return Result.success(result);
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
     * 把中文与英文状态写法归一成业务状态，兼容历史数据和不同调用方。
     */
    private String normalizeStatus(String status) {
        return switch (status) {
            case "PENDING", "未下载" -> "PENDING";
            case "DOWNLOADING", "下载中" -> "DOWNLOADING";
            case "DOWNLOADED", "已下载" -> "DOWNLOADED";
            case "WAITING_KOMGA", "等待 Komga" -> "WAITING_KOMGA";
            case "PARTIAL", "不完整" -> "PARTIAL";
            case "DOWNLOAD_FAILED", "下载失败" -> "DOWNLOAD_FAILED";
            case "KOMGA_IMPORT_FAILED", "Komga 入库失败" -> "KOMGA_IMPORT_FAILED";
            case "IMPORTED", "已入库" -> "IMPORTED";
            case "REVIEW_REQUIRED", "待去重审核" -> "REVIEW_REQUIRED";
            case "BLOCKED", "阻断" -> "BLOCKED";
            case "IGNORED", "已忽略" -> "IGNORED";
            default -> null;
        };
    }

    /**
     * 将内部状态转换为对应中文名称，便于与历史存储值匹配。
     */
    private String statusLabel(String status) {
        return switch (status) {
            case "PENDING" -> "未下载";
            case "DOWNLOADING" -> "下载中";
            case "DOWNLOADED" -> "已下载";
            case "WAITING_KOMGA" -> "等待 Komga";
            case "PARTIAL" -> "不完整";
            case "DOWNLOAD_FAILED" -> "下载失败";
            case "KOMGA_IMPORT_FAILED" -> "Komga 入库失败";
            case "IMPORTED" -> "已入库";
            case "REVIEW_REQUIRED" -> "待去重审核";
            case "BLOCKED" -> "阻断";
            case "IGNORED" -> "已忽略";
            default -> status;
        };
    }

    /**
     * 为标题或标签输入返回数量受限的检索建议，帮助用户快速选择条件。
     */
    @GetMapping("/suggestions")
    public Result<List<Map<String, String>>> getSuggestions(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "all") String type) {

        String query = q == null ? "" : q.trim();
        if (query.isEmpty()) {
            return Result.success(List.of());
        }

        int safeLimit = Math.min(Math.max(limit, 1), 30);
        List<Map<String, String>> suggestions = new ArrayList<>();
        String lowerQ = query.toLowerCase(Locale.ROOT);
        String requestedType = type.toLowerCase(Locale.ROOT);
        if (!Set.of("all", "title", "tag").contains(requestedType)) {
            return Result.error(400, "不支持的联想类型");
        }

        if (!"tag".equals(requestedType)) {
            QueryWrapper<EhGalleriesEntity> titleWrapper = new QueryWrapper<>();
            titleWrapper.select("DISTINCT title")
                    .like("title", query)
                    .last("LIMIT " + safeLimit);
            List<EhGalleriesEntity> titles = galleriesService.list(titleWrapper);
            for (EhGalleriesEntity e : titles) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("value", e.getTitle());
                item.put("type", "title");
                suggestions.add(item);
            }
        }

        if (!"title".equals(requestedType)) {
            Map<String, String> translationMap = tagTranslationService.getTranslationMap();
            int tagCount = 0;
            for (Map.Entry<String, String> entry : translationMap.entrySet()) {
                if (tagCount >= safeLimit) break;
                if (entry.getKey().toLowerCase(Locale.ROOT).contains(lowerQ) || entry.getValue().contains(query)) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("value", entry.getKey());
                    item.put("label", entry.getValue() + " (" + entry.getKey() + ")");
                    item.put("type", "tag");
                    suggestions.add(item);
                    tagCount++;
                }
            }
        }

        return Result.success(suggestions);
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
