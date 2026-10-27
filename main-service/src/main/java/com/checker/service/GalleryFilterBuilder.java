package com.checker.service;

import com.checker.dto.GallerySearchRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared, parameterized predicates for gallery queries and aggregate drilldowns. */
public final class GalleryFilterBuilder {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern KEYWORDS = Pattern.compile("\"([^\"]+)\"|(\\S+)");
    private static final Map<String, String> STATUS_LABELS = Map.ofEntries(
            Map.entry("PENDING", "未下载"), Map.entry("DOWNLOADING", "下载中"),
            Map.entry("DOWNLOADED", "已下载"), Map.entry("WAITING_KOMGA", "等待 Komga"),
            Map.entry("PARTIAL", "不完整"), Map.entry("DOWNLOAD_FAILED", "下载失败"),
            Map.entry("KOMGA_IMPORT_FAILED", "Komga 入库失败"), Map.entry("IMPORTED", "已入库"),
            Map.entry("REVIEW_REQUIRED", "待去重审核"), Map.entry("BLOCKED", "阻断"), Map.entry("IGNORED", "已忽略"));
    public static final String ASSIGNED = "EXISTS (SELECT 1 FROM eh_gallery_collection_items ci WHERE ci.gid = g.gid)";
    public static final String LINKED = "(g.komga_book_id IS NOT NULL AND TRIM(g.komga_book_id) <> '')";

    private GalleryFilterBuilder() {}

    public static SqlFilter build(GallerySearchRequest query) {
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        String status = normalizeStatus(query.getStatus());
        if (status != null) add(clauses, args, "g.download_status IN (?, ?)", status, STATUS_LABELS.get(status));
        if (notBlank(query.getGid())) {
            List<Long> gids;
            try {
                gids = Arrays.stream(query.getGid().trim().split("[\\s,，;；]+")).map(Long::parseLong).distinct().toList();
            } catch (NumberFormatException failure) { throw new IllegalArgumentException("GID 必须是正整数，可用逗号、分号或空格分隔"); }
            if (gids.isEmpty() || gids.size() > 1000 || gids.stream().anyMatch(gid -> gid <= 0)) throw new IllegalArgumentException("请输入 1–1000 个有效的正整数 GID");
            add(clauses, args, "g.gid IN (" + placeholders(gids.size()) + ")", gids.toArray());
        }

        String mode = option(query.getKeywordMode(), "contains", Set.of("contains", "all", "any", "exact"), "关键词匹配方式");
        if (notBlank(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            if (keyword.length() > 500) throw new IllegalArgumentException("关键词最多 500 个字符");
            if ("exact".equals(mode)) {
                add(clauses, args, "(g.title = ? OR g.original_title = ? OR g.filename = ?)", keyword, keyword, keyword);
            } else {
                List<String> words = new ArrayList<>();
                if ("contains".equals(mode)) words.add(keyword);
                else {
                    Matcher matcher = KEYWORDS.matcher(keyword);
                    while (matcher.find()) words.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
                    if (words.size() > 12) throw new IllegalArgumentException("最多支持 12 个检索词；双引号内的短语作为一个检索词");
                }
                List<String> wordClauses = new ArrayList<>();
                for (String word : words) {
                    wordClauses.add("(g.title LIKE ? ESCAPE '=' OR g.original_title LIKE ? ESCAPE '=' OR g.filename LIKE ? ESCAPE '=')");
                    String pattern = "%" + escapeLike(word) + "%";
                    args.add(pattern); args.add(pattern); args.add(pattern);
                }
                clauses.add("(" + String.join("any".equals(mode) ? " OR " : " AND ", wordClauses) + ")");
            }
        }

        String tagMode = option(query.getTagMode(), "all", Set.of("all", "any"), "标签匹配方式");
        Set<String> included = new LinkedHashSet<>(readTags(query.getTags()));
        if (notBlank(query.getTag())) included.add(query.getTag().trim());
        validateTags(included);
        if (!included.isEmpty()) {
            List<String> tagClauses = new ArrayList<>();
            included.forEach(tag -> { tagClauses.add("JSON_CONTAINS(COALESCE(g.tags, JSON_ARRAY()), JSON_QUOTE(?))"); args.add(tag); });
            clauses.add("(" + String.join("any".equals(tagMode) ? " OR " : " AND ", tagClauses) + ")");
        }
        for (String tag : readTags(query.getExcludeTags())) add(clauses, args, "NOT JSON_CONTAINS(COALESCE(g.tags, JSON_ARRAY()), JSON_QUOTE(?))", tag);
        switch (option(query.getDedupe(), "preferred", Set.of("preferred", "duplicates", "all"), "作品版本")) {
            case "preferred" -> clauses.add("g.duplicate_of_gid IS NULL");
            case "duplicates" -> clauses.add("g.duplicate_of_gid IS NOT NULL");
            default -> {}
        }
        switch (option(query.getCollectionScope(), "all", Set.of("all", "assigned", "unassigned"), "合集范围")) {
            case "assigned" -> clauses.add(ASSIGNED);
            case "unassigned" -> clauses.add("NOT " + ASSIGNED);
            default -> {}
        }
        if (query.getCollectionId() != null) {
            if (query.getCollectionId() <= 0) throw new IllegalArgumentException("合集 ID 必须为正整数");
            add(clauses, args, "EXISTS (SELECT 1 FROM eh_gallery_collection_items ci WHERE ci.gid = g.gid AND ci.collection_id = ?)", query.getCollectionId());
        }
        range(clauses, args, "g.rating", query.getMinRating(), query.getMaxRating(), 0, 5, "评分");
        range(clauses, args, "g.page_count", query.getMinPages(), query.getMaxPages(), 0, Integer.MAX_VALUE, "页数");
        range(clauses, args, "g.file_size_mb", query.getMinSizeMb(), query.getMaxSizeMb(), 0, Double.MAX_VALUE, "文件大小");
        LocalDate from = date(query.getFrom(), "开始日期"), to = date(query.getTo(), "结束日期");
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("开始日期不能晚于结束日期");
        if (from != null) add(clauses, args, "g.crawled_at >= ?", from.toString());
        if (to != null) add(clauses, args, "g.crawled_at < ?", to.plusDays(1).toString());
        if (notBlank(query.getSearchQuery())) add(clauses, args, "g.search_query = ?", query.getSearchQuery().trim());
        switch (option(query.getKomga(), "all", Set.of("all", "linked", "unlinked"), "Komga 关联")) {
            case "linked" -> clauses.add(LINKED);
            case "unlinked" -> clauses.add("NOT " + LINKED);
            default -> {}
        }
        addBucket(clauses, query.getRatingBucket(), Map.of(
                "unrated", "(g.rating IS NULL OR g.rating <= 0)", "under3", "g.rating > 0 AND g.rating < 3",
                "3to4", "g.rating >= 3 AND g.rating < 4", "4to4_5", "g.rating >= 4 AND g.rating < 4.5",
                "4_5plus", "g.rating >= 4.5"), "评分区间");
        addBucket(clauses, query.getPageBucket(), Map.of(
                "unknown", "(g.page_count IS NULL OR g.page_count <= 0)", "under20", "g.page_count > 0 AND g.page_count < 20",
                "20to49", "g.page_count >= 20 AND g.page_count < 50", "50to99", "g.page_count >= 50 AND g.page_count < 100",
                "100plus", "g.page_count >= 100"), "页数区间");
        addBucket(clauses, query.getSizeBucket(), Map.of(
                "unknown", "(g.file_size_mb IS NULL OR g.file_size_mb <= 0)", "under50", "g.file_size_mb > 0 AND g.file_size_mb < 50",
                "50to100", "g.file_size_mb >= 50 AND g.file_size_mb < 100", "100to200", "g.file_size_mb >= 100 AND g.file_size_mb < 200",
                "200to500", "g.file_size_mb >= 200 AND g.file_size_mb < 500", "500to1024", "g.file_size_mb >= 500 AND g.file_size_mb < 1024",
                "1024plus", "g.file_size_mb >= 1024"), "体积区间");
        addBucket(clauses, query.getQuality(), Map.of(
                "missingTags", "(g.tags IS NULL OR JSON_LENGTH(g.tags) = 0)",
                "missingRating", "(g.rating IS NULL OR g.rating <= 0)",
                "missingPages", "(g.page_count IS NULL OR g.page_count <= 0)",
                "missingSummary", "(g.summary IS NULL OR TRIM(g.summary) = '')",
                "missingSource", "(g.search_query IS NULL OR TRIM(g.search_query) = '')"), "元数据条件");
        return new SqlFilter(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), List.copyOf(args), from, to);
    }

    public static String normalizeStatus(String value) {
        if (!notBlank(value)) return null;
        String status = value.trim();
        if (STATUS_LABELS.containsKey(status)) return status;
        return STATUS_LABELS.entrySet().stream().filter(entry -> entry.getValue().equals(status)).map(Map.Entry::getKey).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的下载状态"));
    }

    public static String statusCase() {
        StringBuilder sql = new StringBuilder("CASE g.download_status ");
        STATUS_LABELS.forEach((value, label) -> sql.append("WHEN '").append(label).append("' THEN '").append(value).append("' "));
        return sql.append("ELSE COALESCE(NULLIF(g.download_status, ''), 'UNKNOWN') END").toString();
    }

    public static LocalDate date(String value, String label) {
        if (!notBlank(value)) return null;
        try {
            LocalDate parsed = LocalDate.parse(value.trim());
            if (parsed.getYear() < 1000 || parsed.getYear() > 9998) throw new IllegalArgumentException(label + "年份应在 1000–9998 之间");
            return parsed;
        }
        catch (DateTimeParseException failure) { throw new IllegalArgumentException(label + "必须使用 yyyy-MM-dd 格式"); }
    }
    public static String option(String value, String fallback, Set<String> options, String label) {
        String selected = notBlank(value) ? value.trim() : fallback;
        if (!options.contains(selected) && options.contains(selected.toLowerCase(Locale.ROOT))) selected = selected.toLowerCase(Locale.ROOT);
        if (!options.contains(selected)) throw new IllegalArgumentException("不支持的" + label);
        return selected;
    }
    public static String placeholders(int count) { return String.join(",", Collections.nCopies(count, "?")); }
    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private static String escapeLike(String value) { return value.replace("=", "==").replace("%", "=%").replace("_", "=_"); }
    private static void add(List<String> clauses, List<Object> args, String sql, Object... values) { clauses.add(sql); Collections.addAll(args, values); }
    private static void range(List<String> clauses, List<Object> args, String column, Number min, Number max, double low, double high, String label) {
        if (min != null && (!Double.isFinite(min.doubleValue()) || min.doubleValue() < low || min.doubleValue() > high)
                || max != null && (!Double.isFinite(max.doubleValue()) || max.doubleValue() < low || max.doubleValue() > high))
            throw new IllegalArgumentException(label + "范围无效");
        if (min != null && max != null && min.doubleValue() > max.doubleValue()) throw new IllegalArgumentException(label + "下限不能大于上限");
        if (min != null) add(clauses, args, column + " >= ?", min);
        if (max != null) add(clauses, args, column + " <= ?", max);
    }
    private static List<String> readTags(String value) {
        if (!notBlank(value)) return List.of();
        try {
            JsonNode json = JSON.readTree(value);
            if (!json.isArray() || json.size() > 30) throw new IllegalArgumentException("标签必须是最多 30 项的 JSON 字符串数组");
            Set<String> tags = new LinkedHashSet<>();
            for (JsonNode item : json) {
                if (!item.isTextual() || item.asText().isBlank()) throw new IllegalArgumentException("标签必须是非空字符串");
                tags.add(item.asText().trim());
            }
            validateTags(tags);
            return List.copyOf(tags);
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalArgumentException("标签必须是 JSON 字符串数组"); }
    }
    private static void validateTags(Collection<String> tags) {
        if (tags.size() > 30 || tags.stream().anyMatch(tag -> tag.length() > 200)) throw new IllegalArgumentException("最多支持 30 个标签，单个标签最多 200 字符");
    }
    private static void addBucket(List<String> clauses, String value, Map<String, String> options, String label) {
        if (!notBlank(value)) return;
        String predicate = options.get(value);
        if (predicate == null) throw new IllegalArgumentException("不支持的" + label);
        clauses.add("(" + predicate + ")");
    }

    public record SqlFilter(String where, List<Object> args, LocalDate from, LocalDate to) {}
}
