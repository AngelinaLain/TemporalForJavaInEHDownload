package com.checker.controllers;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.checker.common.Result;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.service.EhGalleriesService;
import com.checker.service.EhTagTranslationService;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final EhGalleriesService galleriesService;
    private final EhTagTranslationService tagTranslationService;
    private final EhGalleriesMapper galleriesMapper;

    public DashboardController(EhGalleriesService galleriesService,
                               EhTagTranslationService tagTranslationService,
                               EhGalleriesMapper galleriesMapper) {
        this.galleriesService = galleriesService;
        this.tagTranslationService = tagTranslationService;
        this.galleriesMapper = galleriesMapper;
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
            @RequestParam(required = false) String tag) {

        QueryWrapper<EhGalleriesEntity> wrapper = new QueryWrapper<>();

        if (status != null && !status.isEmpty()) {
            wrapper.eq("download_status", status);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like("title", keyword).or().like("filename", keyword));
        }
        if (tag != null && !tag.isEmpty()) {
            wrapper.apply("JSON_CONTAINS(tags, {0})", '"' + tag.replace("\"", "") + '"');
        }
        wrapper.orderByDesc("crawled_at");

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        IPage<EhGalleriesEntity> pageResult = galleriesService.page(new Page<>(safePage, safeSize), wrapper);
        return Result.success(pageResult);
    }

    /**
     * 搜索联想：根据输入返回匹配的标题和标签建议
     */
    @GetMapping("/suggestions")
    public Result<List<Map<String, String>>> getSuggestions(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit) {

        int safeLimit = Math.min(Math.max(limit, 1), 30);
        List<Map<String, String>> suggestions = new ArrayList<>();
        String lowerQ = q.toLowerCase();

        // 1. 标题联想
        QueryWrapper<EhGalleriesEntity> titleWrapper = new QueryWrapper<>();
        titleWrapper.select("DISTINCT title")
                .like("title", q)
                .last("LIMIT " + safeLimit);
        List<EhGalleriesEntity> titles = galleriesService.list(titleWrapper);
        for (EhGalleriesEntity e : titles) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("value", e.getTitle());
            item.put("type", "title");
            suggestions.add(item);
        }

        // 2. 标签联想（同时匹配英文原名和中文翻译）
        Map<String, String> translationMap = tagTranslationService.getTranslationMap();
        int tagCount = 0;
        for (Map.Entry<String, String> entry : translationMap.entrySet()) {
            if (tagCount >= safeLimit) break;
            if (entry.getKey().toLowerCase().contains(lowerQ) || entry.getValue().contains(q)) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("value", entry.getKey());
                item.put("label", entry.getValue() + " (" + entry.getKey() + ")");
                item.put("type", "tag");
                suggestions.add(item);
                tagCount++;
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
