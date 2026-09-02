package com.checker.controllers;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.checker.common.Result;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.service.EhGalleriesService;
import com.checker.service.EhTagTranslationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dashboard")
@PreAuthorize("hasRole('ADMIN')")
public class DashboardController {

    private final EhGalleriesService galleriesService;
    private final EhTagTranslationService tagTranslationService;
    private final EhGalleriesMapper galleriesMapper;
    private final JdbcTemplate jdbcTemplate;

    public DashboardController(EhGalleriesService galleriesService,
                               EhTagTranslationService tagTranslationService,
                               EhGalleriesMapper galleriesMapper,
                               JdbcTemplate jdbcTemplate) {
        this.galleriesService = galleriesService;
        this.tagTranslationService = tagTranslationService;
        this.galleriesMapper = galleriesMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 总体统计概览
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
     * 下载状态分布（饼图）
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
     * 本地下载进度：返回处于「下载中」的画廊及其已下载字节数/预估大小/百分比。
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
     * 数据库连接状态：SELECT 1 探活 + 基础统计。
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
     * 文件大小分布（柱状图）
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
     * 抓取时间线（按日统计）
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
     * 标签命名空间统计（Top 20）。
     * 使用 MySQL JSON_TABLE 在数据库侧聚合，避免全量加载画廊到内存。
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
     * 画廊列表（分页 + 筛选）
     */
    @GetMapping("/galleries")
    public Result<IPage<EhGalleriesEntity>> getGalleries(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "preferred") String dedupe,
            @RequestParam(defaultValue = "crawledAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder) {

        QueryWrapper<EhGalleriesEntity> wrapper = new QueryWrapper<>();

        if (status != null && !status.isEmpty()) {
            String normalizedStatus = normalizeStatus(status);
            if (normalizedStatus == null) {
                return Result.error(400, "不支持的下载状态");
            }
            wrapper.and(w -> w.eq("download_status", normalizedStatus)
                    .or().eq("download_status", statusLabel(normalizedStatus)));
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like("title", keyword).or().like("filename", keyword));
        }
        if (tag != null && !tag.isEmpty()) {
            wrapper.apply("JSON_CONTAINS(tags, {0})", '"' + tag.replace("\"", "") + '"');
        }
        switch (dedupe.toLowerCase(Locale.ROOT)) {
            case "preferred" -> wrapper.isNull("duplicate_of_gid");
            case "duplicates" -> wrapper.isNotNull("duplicate_of_gid");
            case "all" -> {
                // 显式查看所有版本时不附加条件。
            }
            default -> {
                return Result.error(400, "不支持的作品版本筛选条件");
            }
        }

        String sortColumn = switch (sortBy) {
            case "gid" -> "gid";
            case "title" -> "title";
            case "downloadStatus" -> "download_status";
            case "fileSizeMb" -> "file_size_mb";
            default -> "crawled_at";
        };
        boolean ascending = "asc".equalsIgnoreCase(sortOrder);
        wrapper.orderBy(true, ascending, sortColumn);
        if (!"gid".equals(sortColumn)) {
            wrapper.orderByDesc("gid");
        }

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        IPage<EhGalleriesEntity> pageResult = galleriesService.page(new Page<>(safePage, safeSize), wrapper);
        pageResult.getRecords().forEach(gallery -> {
            String normalizedStatus = normalizeStatus(gallery.getDownloadStatus());
            if (normalizedStatus != null) {
                gallery.setDownloadStatus(normalizedStatus);
            }
        });
        return Result.success(pageResult);
    }

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
     * 搜索联想：根据输入返回匹配的标题和标签建议
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
     * 获取标签翻译映射表（namespace:tag → 中文名）
     */
    @GetMapping("/tag-translations")
    public Result<Map<String, String>> getTagTranslations() {
        return Result.success(tagTranslationService.getTranslationMap());
    }

    /**
     * 手动刷新 EhTag 翻译缓存
     */
    @PostMapping("/tag-translations/refresh")
    public Result<String> refreshTagTranslations() {
        tagTranslationService.refreshCache();
        return Result.success("翻译缓存刷新成功");
    }

    /**
     * 获取标签详情（中文名 + 描述）
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
