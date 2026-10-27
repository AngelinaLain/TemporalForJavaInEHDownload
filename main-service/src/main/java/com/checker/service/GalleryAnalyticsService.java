package com.checker.service;

import com.checker.dto.GalleryAnalyticsRequest;
import com.checker.dto.GallerySearchRequest;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 用六组有规模限制的聚合查询生成概览；按筛选组合短暂缓存，不加载画廊实体或图片。 */
@Service
public class GalleryAnalyticsService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final Cache<Key, Map<String, Object>> cache = Caffeine.newBuilder()
            .maximumSize(64).expireAfterWrite(30, TimeUnit.SECONDS).build();

    public GalleryAnalyticsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 规范化筛选组合并复用短期缓存，返回同一范围内的数据概览。 */
    public Map<String, Object> analyze(GalleryAnalyticsRequest request) {
        if (request.getDays() < 0 || request.getDays() > 3650) throw new IllegalArgumentException("时间范围应在 0–3650 天之间");
        String grain = GalleryFilterBuilder.option(request.getGrain(), "day", Set.of("day", "week", "month"), "时间粒度");
        LocalDate from = GalleryFilterBuilder.date(request.getFrom(), "开始日期");
        LocalDate to = GalleryFilterBuilder.date(request.getTo(), "结束日期");
        if (from != null && to == null) to = today();
        if (request.getDays() > 0 && from == null) {
            if (to == null) to = today();
            from = to.minusDays(request.getDays() - 1L);
        }
        GallerySearchRequest query = new GallerySearchRequest();
        query.setStatus(request.getStatus());
        query.setDedupe(request.getDedupe());
        query.setCollectionScope(request.getCollectionScope());
        query.setCollectionId(request.getCollectionId());
        query.setFrom(from == null ? null : from.toString());
        query.setTo(to == null ? null : to.toString());
        GalleryFilterBuilder.SqlFilter filter = GalleryFilterBuilder.build(query);
        return cache.get(new Key(filter.where(), filter.args(), from, to, grain), this::compute);
    }

    /** 在数据库侧执行各维度聚合，再拼装可继续钻取的统计结果。 */
    private Map<String, Object> compute(Key key) {
        Map<String, String> counts = new LinkedHashMap<>();
        counts.put("duplicates", "g.duplicate_of_gid IS NOT NULL");
        counts.put("imported", "g.download_status IN ('IMPORTED','已入库')");
        counts.put("failed", "g.download_status IN ('DOWNLOAD_FAILED','下载失败','KOMGA_IMPORT_FAILED','Komga 入库失败')");
        counts.put("pending", "g.download_status IN ('PENDING','未下载')");
        counts.put("linked", GalleryFilterBuilder.LINKED);
        counts.put("assigned", GalleryFilterBuilder.ASSIGNED);
        counts.put("missing_tags", "(g.tags IS NULL OR JSON_LENGTH(g.tags) = 0)");
        counts.put("missing_rating", "(g.rating IS NULL OR g.rating <= 0)");
        counts.put("missing_pages", "(g.page_count IS NULL OR g.page_count <= 0)");
        counts.put("missing_summary", "(g.summary IS NULL OR TRIM(g.summary) = '')");
        counts.put("rating_under3", "g.rating > 0 AND g.rating < 3");
        counts.put("rating_3to4", "g.rating >= 3 AND g.rating < 4");
        counts.put("rating_4to4_5", "g.rating >= 4 AND g.rating < 4.5");
        counts.put("rating_4_5plus", "g.rating >= 4.5");
        counts.put("pages_under20", "g.page_count > 0 AND g.page_count < 20");
        counts.put("pages_20to49", "g.page_count >= 20 AND g.page_count < 50");
        counts.put("pages_50to99", "g.page_count >= 50 AND g.page_count < 100");
        counts.put("pages_100plus", "g.page_count >= 100");
        counts.put("size_unknown", "(g.file_size_mb IS NULL OR g.file_size_mb <= 0)");
        counts.put("size_under50", "g.file_size_mb > 0 AND g.file_size_mb < 50");
        counts.put("size_50to100", "g.file_size_mb >= 50 AND g.file_size_mb < 100");
        counts.put("size_100to200", "g.file_size_mb >= 100 AND g.file_size_mb < 200");
        counts.put("size_200to500", "g.file_size_mb >= 200 AND g.file_size_mb < 500");
        counts.put("size_500to1024", "g.file_size_mb >= 500 AND g.file_size_mb < 1024");
        counts.put("size_1024plus", "g.file_size_mb >= 1024");
        StringBuilder aggregateSql = new StringBuilder("SELECT COUNT(*) total, COALESCE(SUM(g.file_size_mb),0) size_mb, " +
                "COALESCE(AVG(NULLIF(g.rating,0)),0) avg_rating, COALESCE(AVG(NULLIF(g.page_count,0)),0) avg_pages, " +
                "DATE_FORMAT(MIN(g.crawled_at),'%Y-%m-%d') first_date");
        counts.forEach((alias, predicate) -> aggregateSql.append(", COALESCE(SUM(CASE WHEN ").append(predicate).append(" THEN 1 ELSE 0 END),0) ").append(alias));
        Object[] args = key.args().toArray();
        Map<String, Object> aggregate = jdbc.queryForMap(aggregateSql + " FROM eh_galleries g" + key.where(), args);
        Map<String, Object> result = new LinkedHashMap<>(), overview = new LinkedHashMap<>();
        for (String field : List.of("total", "size_mb", "avg_rating", "avg_pages", "duplicates", "imported", "failed", "pending", "linked", "assigned", "missing_tags", "missing_rating", "missing_pages", "missing_summary"))
            overview.put(field, aggregate.getOrDefault(field, 0));
        result.put("overview", overview);
        List<Map<String, Object>> status = jdbc.queryForList("SELECT " + GalleryFilterBuilder.statusCase() + " name, COUNT(*) value " +
                "FROM eh_galleries g" + key.where() + " GROUP BY name ORDER BY value DESC", args);
        status.forEach(row -> row.put("filter", Map.of("status", row.get("name"))));
        result.put("status", status);
        result.put("rating", List.of(
                item("未评分", number(aggregate, "missing_rating"), "ratingBucket", "unrated"),
                item("0–3", number(aggregate, "rating_under3"), "ratingBucket", "under3"),
                item("3–4", number(aggregate, "rating_3to4"), "ratingBucket", "3to4"),
                item("4–4.5", number(aggregate, "rating_4to4_5"), "ratingBucket", "4to4_5"),
                item("4.5–5", number(aggregate, "rating_4_5plus"), "ratingBucket", "4_5plus")));
        result.put("pages", List.of(
                item("未知", number(aggregate, "missing_pages"), "pageBucket", "unknown"),
                item("<20页", number(aggregate, "pages_under20"), "pageBucket", "under20"),
                item("20–49页", number(aggregate, "pages_20to49"), "pageBucket", "20to49"),
                item("50–99页", number(aggregate, "pages_50to99"), "pageBucket", "50to99"),
                item("100页+", number(aggregate, "pages_100plus"), "pageBucket", "100plus")));
        result.put("size", List.of(
                item("未知", number(aggregate, "size_unknown"), "sizeBucket", "unknown"),
                item("<50MB", number(aggregate, "size_under50"), "sizeBucket", "under50"),
                item("50–100MB", number(aggregate, "size_50to100"), "sizeBucket", "50to100"),
                item("100–200MB", number(aggregate, "size_100to200"), "sizeBucket", "100to200"),
                item("200–500MB", number(aggregate, "size_200to500"), "sizeBucket", "200to500"),
                item("500MB–1GB", number(aggregate, "size_500to1024"), "sizeBucket", "500to1024"),
                item("≥1GB", number(aggregate, "size_1024plus"), "sizeBucket", "1024plus")));
        result.put("dedupe", List.of(
                item("首选版本", number(aggregate, "total") - number(aggregate, "duplicates"), "dedupe", "preferred"),
                item("重复版本", number(aggregate, "duplicates"), "dedupe", "duplicates")));
        result.put("quality", List.of(
                item("缺少标签", number(aggregate, "missing_tags"), "quality", "missingTags"),
                item("缺少评分", number(aggregate, "missing_rating"), "quality", "missingRating"),
                item("缺少页数", number(aggregate, "missing_pages"), "quality", "missingPages"),
                item("缺少摘要", number(aggregate, "missing_summary"), "quality", "missingSummary")));
        List<Map<String, Object>> sources = jdbc.queryForList("SELECT COALESCE(NULLIF(TRIM(g.search_query),''),'未记录') name, COUNT(*) value " +
                "FROM eh_galleries g" + key.where() + " GROUP BY name ORDER BY value DESC LIMIT 8", args);
        sources.forEach(row -> row.put("filter", "未记录".equals(row.get("name")) ? Map.of("quality", "missingSource") : Map.of("searchQuery", row.get("name"))));
        result.put("queries", sources);
        List<Map<String, Object>> collections = jdbc.queryForList("SELECT c.id, COALESCE(c.name,'未归入合集') name, COUNT(*) value " +
                "FROM eh_galleries g LEFT JOIN eh_gallery_collection_items ci ON ci.gid = g.gid " +
                "LEFT JOIN eh_gallery_collections c ON c.id = ci.collection_id" + key.where() +
                " GROUP BY c.id, c.name ORDER BY value DESC LIMIT 10", args);
        collections.forEach(row -> row.put("filter", row.get("id") == null ? Map.of("collectionScope", "unassigned") : Map.of("collectionId", row.get("id"))));
        result.put("collections", collections);
        List<Map<String, Object>> languages = jdbc.queryForList("SELECT SUBSTRING(jt.tag,10) name, COUNT(DISTINCT g.gid) value " +
                "FROM eh_galleries g JOIN JSON_TABLE(g.tags,'$[*]' COLUMNS(tag VARCHAR(200) PATH '$')) jt ON jt.tag LIKE 'language:%'" +
                key.where() + " GROUP BY name ORDER BY value DESC LIMIT 12", args);
        languages.forEach(row -> row.put("filter", Map.of("tag", "language:" + row.get("name"))));
        result.put("languages", languages);
        fillTimeline(result, key, aggregate);
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("from", key.from() == null ? null : key.from().toString());
        range.put("to", key.to() == null ? null : key.to().toString());
        range.put("grain", key.grain());
        result.put("range", range);
        result.put("generatedAt", Instant.now().toString());
        return result;
    }

    /** 补齐时间线上没有记录的日期段，使图表不会把空缺日期连错。 */
    private void fillTimeline(Map<String, Object> result, Key key, Map<String, Object> aggregate) {
        LocalDate end = key.to() == null ? today() : key.to();
        LocalDate oldest = aggregate.get("first_date") == null ? end : LocalDate.parse(String.valueOf(aggregate.get("first_date")));
        LocalDate start = key.from() == null ? oldest : key.from();
        LocalDate maximumStart = switch (key.grain()) {
            case "week" -> periodStart(end, "week").minusWeeks(103);
            case "month" -> end.withDayOfMonth(1).minusMonths(119);
            default -> end.minusDays(179);
        };
        boolean truncated = start.isBefore(maximumStart);
        if (truncated) start = maximumStart;
        if (start.isAfter(end)) start = end;
        LocalDate bucketStart = periodStart(start, key.grain());
        String bucketSql = switch (key.grain()) {
            case "week" -> "DATE_FORMAT(DATE_SUB(DATE(g.crawled_at),INTERVAL WEEKDAY(g.crawled_at) DAY),'%Y-%m-%d')";
            case "month" -> "DATE_FORMAT(g.crawled_at,'%Y-%m-01')";
            default -> "DATE_FORMAT(g.crawled_at,'%Y-%m-%d')";
        };
        List<Object> args = new ArrayList<>(key.args());
        args.add(start.toString());
        args.add(end.plusDays(1).toString());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + bucketSql + " date, COUNT(*) value, COALESCE(SUM(g.file_size_mb),0) size_mb " +
                "FROM eh_galleries g" + key.where() + (key.where().isBlank() ? " WHERE " : " AND ") +
                "g.crawled_at >= ? AND g.crawled_at < ? GROUP BY date ORDER BY date", args.toArray());
        Map<String, Map<String, Object>> byDate = new HashMap<>();
        rows.forEach(row -> byDate.put(String.valueOf(row.get("date")), row));
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (LocalDate date = bucketStart; !date.isAfter(end); date = nextPeriod(date, key.grain())) {
            Map<String, Object> row = byDate.getOrDefault(date.toString(), Map.of("value", 0L, "size_mb", 0D));
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", date.toString());
            point.put("value", row.get("value"));
            point.put("size_mb", row.get("size_mb"));
            point.put("filter", Map.of("from", date.isBefore(start) ? start.toString() : date.toString(),
                    "to", nextPeriod(date, key.grain()).minusDays(1).isAfter(end) ? end.toString() : nextPeriod(date, key.grain()).minusDays(1).toString()));
            timeline.add(point);
        }
        result.put("timeline", timeline);
        result.put("timelineRange", Map.of("from", start.toString(), "to", end.toString(), "grain", key.grain(), "truncated", truncated));
    }

    private static LocalDate today() { return LocalDate.now(BUSINESS_ZONE); }
    private static LocalDate periodStart(LocalDate date, String grain) {
        return switch (grain) { case "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); case "month" -> date.withDayOfMonth(1); default -> date; };
    }
    private static LocalDate nextPeriod(LocalDate date, String grain) {
        return switch (grain) { case "week" -> date.plusWeeks(1); case "month" -> date.plusMonths(1); default -> date.plusDays(1); };
    }
    private static long number(Map<String, Object> map, String field) { return map.get(field) instanceof Number value ? value.longValue() : 0; }
    private static Map<String, Object> item(String name, long value, String filter, String selected) {
        return Map.of("name", name, "value", value, "filter", Map.of(filter, selected));
    }
    private record Key(String where, List<Object> args, LocalDate from, LocalDate to, String grain) {}
}
