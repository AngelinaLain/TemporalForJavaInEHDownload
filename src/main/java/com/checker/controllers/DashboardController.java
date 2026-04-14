package com.checker.controllers;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.checker.common.Result;
import com.checker.entity.EhGalleriesEntity;
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

    public DashboardController(EhGalleriesService galleriesService,
                               EhTagTranslationService tagTranslationService) {
        this.galleriesService = galleriesService;
        this.tagTranslationService = tagTranslationService;
    }

    /**
     * 总体统计概览
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        long total = galleriesService.count();

        QueryWrapper<EhGalleriesEntity> downloadedWrapper = new QueryWrapper<>();
        downloadedWrapper.eq("download_status", "已下载");
        long downloaded = galleriesService.count(downloadedWrapper);

        QueryWrapper<EhGalleriesEntity> importedWrapper = new QueryWrapper<>();
        importedWrapper.eq("download_status", "已入库");
        long imported = galleriesService.count(importedWrapper);

        QueryWrapper<EhGalleriesEntity> failedWrapper = new QueryWrapper<>();
        failedWrapper.eq("download_status", "下载失败");
        long failed = galleriesService.count(failedWrapper);

        QueryWrapper<EhGalleriesEntity> pendingWrapper = new QueryWrapper<>();
        pendingWrapper.eq("download_status", "未下载");
        long pending = galleriesService.count(pendingWrapper);

        QueryWrapper<EhGalleriesEntity> sizeWrapper = new QueryWrapper<>();
        sizeWrapper.isNotNull("file_size_mb").select("COALESCE(SUM(file_size_mb), 0) as file_size_mb");
        Map<String, Object> sizeMap = galleriesService.getMap(sizeWrapper);
        double totalSizeMb = 0;
        if (sizeMap != null && sizeMap.get("file_size_mb") != null) {
            totalSizeMb = ((Number) sizeMap.get("file_size_mb")).doubleValue();
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("downloaded", downloaded);
        stats.put("imported", imported);
        stats.put("failed", failed);
        stats.put("pending", pending);
        stats.put("totalSizeGb", Math.round(totalSizeMb / 1024 * 100.0) / 100.0);

        return Result.success(stats);
    }

    /**
     * 下载状态分布（饼图）
     */
    @GetMapping("/status-distribution")
    public Result<List<Map<String, Object>>> getStatusDistribution() {
        List<Map<String, Object>> result = new ArrayList<>();
        String[] statuses = {"未下载", "下载中", "已下载", "下载失败", "已入库", "阻断", "已忽略"};

        for (String status : statuses) {
            QueryWrapper<EhGalleriesEntity> wrapper = new QueryWrapper<>();
            wrapper.eq("download_status", status);
            long count = galleriesService.count(wrapper);
            if (count > 0) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", status);
                item.put("value", count);
                result.add(item);
            }
        }
        return Result.success(result);
    }

    /**
     * 文件大小分布（柱状图）
     */
    @GetMapping("/file-size-distribution")
    public Result<Map<String, Object>> getFileSizeDistribution() {
        QueryWrapper<EhGalleriesEntity> wrapper = new QueryWrapper<>();
        wrapper.isNotNull("file_size_mb").gt("file_size_mb", 0);
        List<EhGalleriesEntity> galleries = galleriesService.list(wrapper);

        // 按大小分桶: <50MB, 50-100, 100-200, 200-500, 500-1000, >1GB
        String[] labels = {"<50MB", "50-100MB", "100-200MB", "200-500MB", "500MB-1GB", ">1GB"};
        long[] counts = new long[6];

        for (EhGalleriesEntity g : galleries) {
            double size = g.getFileSizeMb();
            if (size < 50) counts[0]++;
            else if (size < 100) counts[1]++;
            else if (size < 200) counts[2]++;
            else if (size < 500) counts[3]++;
            else if (size < 1024) counts[4]++;
            else counts[5]++;
        }

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
     * 标签分类统计（取 Top 20 标签前缀分类）
     */
    @GetMapping("/tag-stats")
    public Result<List<Map<String, Object>>> getTagStats() {
        QueryWrapper<EhGalleriesEntity> wrapper = new QueryWrapper<>();
        wrapper.isNotNull("tags").select("tags");
        List<EhGalleriesEntity> galleries = galleriesService.list(wrapper);

        // 统计标签命名空间: "female:", "male:", "language:", "artist:", etc.
        Map<String, Long> namespaceCounts = new HashMap<>();
        for (EhGalleriesEntity g : galleries) {
            if (g.getTags() != null) {
                for (String tag : g.getTags()) {
                    String namespace = tag.contains(":") ? tag.substring(0, tag.indexOf(":")) : "misc";
                    namespaceCounts.merge(namespace, 1L, Long::sum);
                }
            }
        }

        List<Map<String, Object>> result = namespaceCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(20)
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", e.getKey());
                    item.put("nameCn", tagTranslationService.translateNamespace(e.getKey()));
                    item.put("value", e.getValue());
                    return item;
                })
                .collect(Collectors.toList());

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

        IPage<EhGalleriesEntity> pageResult = galleriesService.page(new Page<>(page, size), wrapper);
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
