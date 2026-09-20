package com.checker.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.checker.common.GallerySeriesMatching;
import com.checker.common.PerceptualHash;
import com.checker.dto.GalleryCollectionCandidate;
import com.checker.dto.GalleryCollectionRequest;
import com.checker.dto.GalleryCollectionSummary;
import com.checker.entity.EhGalleriesEntity;
import com.checker.entity.GalleryCollectionEntity;
import com.checker.entity.GalleryPageHashEntity;
import com.checker.mapper.GalleryCollectionMapper;
import com.checker.mapper.GalleryPageHashMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class GalleryCollectionService {
    private final GalleryCollectionMapper collectionMapper;
    private final EhGalleriesService galleriesService;
    private final GalleryPageHashMapper pageHashMapper;
    private final JdbcTemplate jdbcTemplate;

    public GalleryCollectionService(GalleryCollectionMapper collectionMapper,
                                    EhGalleriesService galleriesService,
                                    GalleryPageHashMapper pageHashMapper,
                                    JdbcTemplate jdbcTemplate) {
        this.collectionMapper = collectionMapper;
        this.galleriesService = galleriesService;
        this.pageHashMapper = pageHashMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<GalleryCollectionSummary> listCollections() {
        return jdbcTemplate.query("""
                SELECT c.id, c.name, c.description, c.updated_at, COUNT(i.gid) item_count
                FROM eh_gallery_collections c
                LEFT JOIN eh_gallery_collection_items i ON i.collection_id = c.id
                GROUP BY c.id, c.name, c.description, c.updated_at
                ORDER BY c.updated_at DESC, c.id DESC
                """, (rs, row) -> GalleryCollectionSummary.builder()
                .id(rs.getLong("id")).name(rs.getString("name"))
                .description(rs.getString("description")).itemCount(rs.getLong("item_count"))
                .updatedAt(rs.getTimestamp("updated_at")).build());
    }

    @Transactional
    public GalleryCollectionEntity create(GalleryCollectionRequest request) {
        GalleryCollectionEntity entity = new GalleryCollectionEntity();
        entity.setName(cleanName(request.getName()));
        entity.setDescription(cleanDescription(request.getDescription()));
        try {
            collectionMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("已存在同名合集");
        }
        return entity;
    }

    @Transactional
    public GalleryCollectionEntity update(long id, GalleryCollectionRequest request) {
        GalleryCollectionEntity entity = requireCollection(id);
        entity.setName(cleanName(request.getName()));
        entity.setDescription(cleanDescription(request.getDescription()));
        try {
            collectionMapper.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("已存在同名合集");
        }
        return entity;
    }

    @Transactional
    public void delete(long id) {
        requireCollection(id);
        collectionMapper.deleteById(id);
    }

    public List<GalleryCollectionCandidate> listItems(long collectionId) {
        requireCollection(collectionId);
        List<Long> gids = jdbcTemplate.queryForList(
                "SELECT gid FROM eh_gallery_collection_items WHERE collection_id = ? ORDER BY sort_order, gid",
                Long.class, collectionId);
        if (gids.isEmpty()) return List.of();
        Map<Long, EhGalleriesEntity> galleries = galleriesService.listByIds(gids).stream()
                .collect(Collectors.toMap(EhGalleriesEntity::getGid, Function.identity()));
        return gids.stream().map(galleries::get).filter(item -> item != null)
                .map(item -> toCandidate(item, collectionId, null)).toList();
    }

    public List<GalleryCollectionCandidate> searchGalleries(String keyword, String scope, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        QueryWrapper<EhGalleriesEntity> query = new QueryWrapper<>();
        query.isNull("duplicate_of_gid");
        if (keyword != null && !keyword.isBlank()) {
            String value = keyword.trim();
            query.and(w -> w.like("title", value).or().like("original_title", value).or().like("filename", value));
        }
        query.orderByDesc("crawled_at").last("LIMIT " + safeLimit * 3);
        List<EhGalleriesEntity> galleries = galleriesService.list(query);
        Map<Long, Membership> memberships = memberships(galleries.stream().map(EhGalleriesEntity::getGid).toList());
        boolean unassignedOnly = "unassigned".equalsIgnoreCase(scope);
        return galleries.stream()
                .filter(item -> !unassignedOnly || !memberships.containsKey(item.getGid()))
                .limit(safeLimit)
                .map(item -> {
                    Membership membership = memberships.get(item.getGid());
                    return toCandidate(item, membership == null ? null : membership.collectionId(),
                            membership == null ? null : membership.collectionName());
                }).toList();
    }

    @Transactional
    public void addItems(long collectionId, List<Long> gids, String source) {
        requireCollection(collectionId);
        List<Long> cleanGids = gids == null ? List.of() : gids.stream().filter(id -> id != null).distinct().toList();
        if (cleanGids.isEmpty()) throw new IllegalArgumentException("至少选择一个画廊");
        Set<Long> existing = galleriesService.listByIds(cleanGids).stream()
                .map(EhGalleriesEntity::getGid).collect(Collectors.toSet());
        if (existing.size() != cleanGids.size()) throw new IllegalArgumentException("部分画廊不存在");
        String normalizedSource = "SUGGESTED".equalsIgnoreCase(source) ? "SUGGESTED" : "MANUAL";
        int nextOrder = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM eh_gallery_collection_items WHERE collection_id = ?",
                Integer.class, collectionId);
        for (Long gid : cleanGids) {
            jdbcTemplate.update("""
                    INSERT INTO eh_gallery_collection_items(collection_id, gid, sort_order, added_source)
                    VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE collection_id = VALUES(collection_id),
                      sort_order = VALUES(sort_order), added_source = VALUES(added_source), created_at = CURRENT_TIMESTAMP
                    """, collectionId, gid, nextOrder++, normalizedSource);
        }
        jdbcTemplate.update("UPDATE eh_gallery_collections SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", collectionId);
    }

    @Transactional
    public void removeItem(long collectionId, long gid) {
        requireCollection(collectionId);
        jdbcTemplate.update("DELETE FROM eh_gallery_collection_items WHERE collection_id = ? AND gid = ?",
                collectionId, gid);
        jdbcTemplate.update("UPDATE eh_gallery_collections SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", collectionId);
    }

    public List<GalleryCollectionCandidate> suggestions(long collectionId, int limit) {
        List<GalleryCollectionCandidate> current = listItems(collectionId);
        if (current.isEmpty()) return List.of();
        List<Long> memberGids = current.stream().map(GalleryCollectionCandidate::getGid).toList();
        List<EhGalleriesEntity> members = galleriesService.listByIds(memberGids);

        QueryWrapper<EhGalleriesEntity> query = new QueryWrapper<>();
        query.isNull("duplicate_of_gid")
                .notInSql("gid", "SELECT gid FROM eh_gallery_collection_items")
                .orderByDesc("crawled_at")
                .last("LIMIT 2000");
        List<EhGalleriesEntity> candidates = galleriesService.list(query);
        List<Long> allGids = new ArrayList<>(memberGids);
        allGids.addAll(candidates.stream().map(EhGalleriesEntity::getGid).toList());
        Map<Long, String> coverHashes = firstPageHashes(allGids);

        List<GalleryCollectionCandidate> scored = new ArrayList<>();
        for (EhGalleriesEntity candidate : candidates) {
            GallerySeriesMatching.SeriesMatch best = null;
            for (EhGalleriesEntity member : members) {
                GallerySeriesMatching.SeriesMatch match = GallerySeriesMatching.score(
                        member, coverHashes.get(member.getGid()), candidate, coverHashes.get(candidate.getGid()));
                if (best == null || match.score() > best.score()) best = match;
            }
            if (best != null && best.score() >= 48) {
                scored.add(GalleryCollectionCandidate.builder()
                        .gid(candidate.getGid()).title(candidate.getTitle()).originalTitle(candidate.getOriginalTitle())
                        .galleryUrl(candidate.getGalleryUrl()).pageCount(candidate.getPageCount()).rating(candidate.getRating())
                        .score(best.score()).titleSimilarity(best.titleSimilarity())
                        .coverSimilarity(best.coverSimilarity()).metadataSimilarity(best.metadataSimilarity())
                        .reason(best.reason()).build());
            }
        }
        return scored.stream().sorted(Comparator.comparing(GalleryCollectionCandidate::getScore).reversed()
                        .thenComparing(GalleryCollectionCandidate::getGid, Comparator.reverseOrder()))
                .limit(Math.min(Math.max(limit, 1), 100)).toList();
    }

    private Map<Long, String> firstPageHashes(List<Long> gids) {
        if (gids.isEmpty()) return Map.of();
        QueryWrapper<GalleryPageHashEntity> query = new QueryWrapper<>();
        query.in("gid", gids).eq("algorithm_version", PerceptualHash.ALGORITHM_VERSION)
                .orderByAsc("gid", "page_index");
        Map<Long, String> result = new LinkedHashMap<>();
        for (GalleryPageHashEntity hash : pageHashMapper.selectList(query)) {
            result.putIfAbsent(hash.getGid(), hash.getPerceptualHash());
        }
        return result;
    }

    private Map<Long, Membership> memberships(List<Long> gids) {
        if (gids.isEmpty()) return Map.of();
        String placeholders = gids.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = "SELECT i.gid, c.id, c.name FROM eh_gallery_collection_items i "
                + "JOIN eh_gallery_collections c ON c.id = i.collection_id WHERE i.gid IN (" + placeholders + ")";
        Map<Long, Membership> result = new LinkedHashMap<>();
        jdbcTemplate.query(sql, gids.toArray(), rs -> {
            result.put(rs.getLong("gid"), new Membership(rs.getLong("id"), rs.getString("name")));
        });
        return result;
    }

    private GalleryCollectionCandidate toCandidate(EhGalleriesEntity gallery, Long collectionId, String collectionName) {
        return GalleryCollectionCandidate.builder().gid(gallery.getGid()).title(gallery.getTitle())
                .originalTitle(gallery.getOriginalTitle()).galleryUrl(gallery.getGalleryUrl())
                .pageCount(gallery.getPageCount()).rating(gallery.getRating())
                .collectionId(collectionId).collectionName(collectionName).build();
    }

    private GalleryCollectionEntity requireCollection(long id) {
        GalleryCollectionEntity entity = collectionMapper.selectById(id);
        if (entity == null) throw new IllegalArgumentException("合集不存在");
        return entity;
    }

    private String cleanName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("合集名称不能为空");
        return value.trim();
    }

    private String cleanDescription(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Membership(Long collectionId, String collectionName) {
    }
}
