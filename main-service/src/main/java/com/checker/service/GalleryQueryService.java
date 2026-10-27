package com.checker.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.checker.dto.GallerySearchRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class GalleryQueryService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> SORTS = Map.of(
            "gid", "g.gid", "title", "g.title", "downloadStatus", "g.download_status",
            "fileSizeMb", "g.file_size_mb", "crawledAt", "g.crawled_at",
            "rating", "g.rating", "pageCount", "g.page_count", "updatedAt", "g.updated_at");
    // Intentionally exclude crawler trace JSON, tokens and persistence-only cleanup journals.
    private static final String COLUMNS = "g.gid, g.title, g.original_title, g.page_count, g.rating, " +
            "g.dedupe_key, g.duplicate_of_gid, g.dedupe_confidence, g.dedupe_match_score, " +
            "g.dedupe_match_reason, g.dedupe_algorithm_version, g.filename, g.storage_path, " +
            "g.gallery_url, g.search_query, g.crawled_at, g.download_status, g.tags, " +
            "g.komga_book_id, g.file_size_mb, g.downloaded_bytes, g.summary, g.updated_at";
    private final JdbcTemplate jdbc;

    public GalleryQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public IPage<Map<String, Object>> search(GallerySearchRequest query) {
        GalleryFilterBuilder.SqlFilter filter = GalleryFilterBuilder.build(query);
        String sort = SORTS.get(query.getSortBy());
        if (sort == null) throw new IllegalArgumentException("不支持的排序字段");
        String order = GalleryFilterBuilder.option(query.getSortOrder(), "desc", Set.of("asc", "desc"), "排序方向");
        int page = Math.max(1, query.getPage()), size = Math.min(100, Math.max(1, query.getSize()));
        Object[] args = filter.args().toArray();
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM eh_galleries g" + filter.where(), Long.class, args);
        Page<Map<String, Object>> result = new Page<>(page, size, total == null ? 0 : total);
        if (result.getTotal() == 0 || (long) (page - 1) * size >= result.getTotal()) {
            result.setRecords(List.of());
            return result;
        }
        List<Object> pageArgs = new ArrayList<>(filter.args());
        pageArgs.add(size);
        pageArgs.add((long) (page - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + COLUMNS + " FROM eh_galleries g" + filter.where() +
                " ORDER BY " + sort + " " + order + ("g.gid".equals(sort) ? "" : ", g.gid DESC") + " LIMIT ? OFFSET ?", pageArgs.toArray());

        List<Map<String, Object>> records = rows.stream().map(this::toRecord).toList();
        if (!records.isEmpty()) {
            List<Object> gids = records.stream().map(row -> row.get("gid")).toList();
            Map<Long, Map<String, Object>> memberships = new HashMap<>();
            jdbc.queryForList("SELECT ci.gid, c.id, c.name FROM eh_gallery_collection_items ci " +
                    "JOIN eh_gallery_collections c ON c.id = ci.collection_id WHERE ci.gid IN (" +
                    GalleryFilterBuilder.placeholders(gids.size()) + ")", gids.toArray())
                    .forEach(row -> memberships.put(((Number) row.get("gid")).longValue(), row));
            records.forEach(record -> {
                Map<String, Object> membership = memberships.get(((Number) record.get("gid")).longValue());
                record.put("collectionId", membership == null ? null : membership.get("id"));
                record.put("collectionName", membership == null ? null : membership.get("name"));
                record.put("collectionType", membership == null ? "STANDALONE" : "SERIES");
            });
        }
        result.setRecords(records);
        return result;
    }

    private Map<String, Object> toRecord(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> result.put(camelCase(key), value));
        Object tags = result.get("tags");
        try {
            if (tags instanceof String text) result.put("tags", JSON.readValue(text, List.class));
            else if (tags instanceof byte[] bytes) result.put("tags", JSON.readValue(bytes, List.class));
            else if (tags == null) result.put("tags", List.of());
        } catch (Exception ignored) { result.put("tags", List.of()); }
        String status = String.valueOf(result.get("downloadStatus"));
        try { result.put("downloadStatus", GalleryFilterBuilder.normalizeStatus(status)); }
        catch (IllegalArgumentException ignored) { /* Unknown historical states remain visible. */ }
        return result;
    }

    private static String camelCase(String column) {
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (char character : column.toLowerCase(Locale.ROOT).toCharArray()) {
            if (character == '_') upper = true;
            else { result.append(upper ? Character.toUpperCase(character) : character); upper = false; }
        }
        return result.toString();
    }
}
