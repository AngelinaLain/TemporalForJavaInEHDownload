package com.checker.service;

import com.checker.common.Constants;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GallerySeriesPlacementService {
    private final JdbcTemplate jdbcTemplate;

    public GallerySeriesPlacementService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SeriesPlacement resolve(Long gid) {
        List<SeriesPlacement> rows = jdbcTemplate.query("""
                SELECT c.id, c.name, i.sort_order
                FROM eh_gallery_collection_items i
                JOIN eh_gallery_collections c ON c.id = i.collection_id
                WHERE i.gid = ?
                """, (rs, row) -> new SeriesPlacement(
                rs.getLong("id"), rs.getString("name"), safeDirectory(rs.getString("name")),
                rs.getInt("sort_order") + 1,
                rs.getLong("id") + ":" + rs.getString("name") + ":" + rs.getInt("sort_order")), gid);
        return rows.isEmpty()
                ? new SeriesPlacement(null, Constants.KOMGA_TARGET_SERIES, "", null, "UNASSIGNED")
                : rows.get(0);
    }

    public static String safeDirectory(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("合集名称不能为空");
        String value = name.trim();
        if (value.matches(".*[<>:\"/\\\\|?*\\p{Cntrl}].*") || value.endsWith(".") || value.endsWith(" ")
                || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("合集名称包含群晖文件夹不支持的字符");
        }
        if (value.length() > 120) throw new IllegalArgumentException("合集名称不能超过 120 个字符");
        return value;
    }

    public record SeriesPlacement(Long collectionId, String seriesName, String relativeDirectory,
                                  Integer number, String signature) {
    }
}
