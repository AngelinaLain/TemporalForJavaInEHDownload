package com.checker.service.catalog;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.checker.dao.catalog.EhGalleriesMapper;
import com.checker.entity.EhGalleriesEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 汇总仪表盘所需的统计、状态、容量与检索建议。
 *
 * <p>Web 层只负责参数接收与结果包装，数据库查询、聚合结果整理和大字段规避都留在本服务内，
 * 避免控制器直接依赖 DAO 层。</p>
 */
@Service
public class GalleryDashboardService {

    private static final int SUGGESTION_LIMIT = 30;

    private final EhGalleriesMapper galleriesMapper;
    private final EhGalleriesService galleriesService;
    private final EhTagTranslationService tagTranslationService;
    private final JdbcTemplate jdbcTemplate;

    public GalleryDashboardService(EhGalleriesMapper galleriesMapper,
                                   EhGalleriesService galleriesService,
                                   EhTagTranslationService tagTranslationService,
                                   JdbcTemplate jdbcTemplate) {
        this.galleriesMapper = galleriesMapper;
        this.galleriesService = galleriesService;
        this.tagTranslationService = tagTranslationService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 返回画廊状态及容量的基础汇总，不逐条加载全部画廊。
     */
    public Map<String, Object> overview() {
        Map<String, Object> overview = galleriesMapper.getDashboardOverview();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", toLong(overview.get("total")));
        stats.put("downloaded", toLong(overview.get("downloaded")));
        stats.put("imported", toLong(overview.get("imported")));
        stats.put("failed", toLong(overview.get("failed")));
        stats.put("pending", toLong(overview.get("pending")));
        stats.put("totalSizeGb", roundGb(overview.get("total_size_mb")));
        return stats;
    }

    /**
     * 把数据库聚合的下载状态整理为状态分布数据。
     */
    public List<Map<String, Object>> statusDistribution() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : galleriesMapper.countByDownloadStatus()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", String.valueOf(row.get("status")));
            item.put("value", toLong(row.get("cnt")));
            result.add(item);
        }
        return result;
    }

    /**
     * 返回当前下载中的画廊及其下载进度，供概览实时展示。
     */
    public List<Map<String, Object>> downloadProgress() {
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
        return result;
    }

    /**
     * 查询数据库连接及容量等信息，失败时返回可识别的服务状态。
     */
    public Map<String, Object> databaseStatus() {
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
            status.put("totalSizeGb", roundGb(overview.get("total_size_mb")));
        } catch (Exception ignored) {
            status.put("total", 0L);
            status.put("totalSizeGb", 0.0);
        }
        return status;
    }

    /**
     * 返回画廊文件体积的区间分布，用于容量图表。
     */
    public Map<String, Object> fileSizeDistribution() {
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
        return result;
    }

    /**
     * 按抓取时间统计画廊数量，供历史趋势图展示。
     */
    public Map<String, Object> crawlTimeline() {
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
        return result;
    }

    /**
     * 汇总标签分类数量，供概览查看内容组成。
     */
    public List<Map<String, Object>> tagStatistics() {
        List<Map<String, Object>> rows = galleriesMapper.countTagNamespaces();
        return rows.stream().map(row -> {
            String namespace = String.valueOf(row.get("namespace"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", namespace);
            item.put("nameCn", tagTranslationService.translateNamespace(namespace));
            item.put("value", ((Number) row.get("cnt")).longValue());
            return item;
        }).collect(Collectors.toList());
    }

    /**
     * 为标题或标签输入返回数量受限的检索建议，帮助用户快速选择条件。
     *
     * @throws IllegalArgumentException 联想类型不在支持范围内时抛出
     */
    public List<Map<String, String>> suggestions(String keyword, int limit, String type) {
        String query = keyword == null ? "" : keyword.trim();
        if (query.isEmpty()) {
            return List.of();
        }

        int safeLimit = Math.min(Math.max(limit, 1), SUGGESTION_LIMIT);
        List<Map<String, String>> suggestions = new ArrayList<>();
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        String requestedType = type.toLowerCase(Locale.ROOT);
        if (!Set.of("all", "title", "tag").contains(requestedType)) {
            throw new IllegalArgumentException("不支持的联想类型");
        }

        if (!"tag".equals(requestedType)) {
            QueryWrapper<EhGalleriesEntity> titleWrapper = new QueryWrapper<>();
            titleWrapper.select("DISTINCT title")
                    .like("title", query)
                    .last("LIMIT " + safeLimit);
            List<EhGalleriesEntity> titles = galleriesService.list(titleWrapper);
            for (EhGalleriesEntity gallery : titles) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("value", gallery.getTitle());
                item.put("type", "title");
                suggestions.add(item);
            }
        }

        if (!"title".equals(requestedType)) {
            Map<String, String> translationMap = tagTranslationService.getTranslationMap();
            int tagCount = 0;
            for (Map.Entry<String, String> entry : translationMap.entrySet()) {
                if (tagCount >= safeLimit) {
                    break;
                }
                if (entry.getKey().toLowerCase(Locale.ROOT).contains(lowerQuery) || entry.getValue().contains(query)) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("value", entry.getKey());
                    item.put("label", entry.getValue() + " (" + entry.getKey() + ")");
                    item.put("type", "tag");
                    suggestions.add(item);
                    tagCount++;
                }
            }
        }

        return suggestions;
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static double roundGb(Object megabytes) {
        return Math.round(toDouble(megabytes) / 1024 * 100.0) / 100.0;
    }
}
