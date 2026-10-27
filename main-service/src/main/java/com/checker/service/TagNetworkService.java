package com.checker.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 提供标签目录和选中标签的直接关联，避免一次计算全库标签的所有两两组合。 */
@Service
public class TagNetworkService {
    private static final int SNAPSHOT_SECONDS = 300;
    private static final long SNAPSHOT_CACHE_BYTES = 64L * 1024 * 1024;
    private static final long SNAPSHOT_MAX_BYTES = 32L * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final EhTagTranslationService translations;
    private final Cache<String, CatalogSnapshot> snapshots;
    private final long snapshotMaxBytes;
    private final Cache<Integer, Map<String, String>> dictionaries = Caffeine.newBuilder()
            .maximumSize(1).expireAfterWrite(30, TimeUnit.SECONDS).build();
    private final Cache<String, List<Map<String, Object>>> catalogs = Caffeine.newBuilder()
            .maximumWeight(16 * 1024 * 1024)
            .weigher((String key, List<Map<String, Object>> value) -> (int) Math.min(Integer.MAX_VALUE,
                    64L + value.stream().mapToLong(row -> 96L + String.valueOf(row.get("name")).length() * 2L).sum()))
            .expireAfterWrite(30, TimeUnit.SECONDS).build();
    private final Cache<NeighborhoodKey, Map<String, Object>> neighborhoods = Caffeine.newBuilder()
            .maximumSize(128).expireAfterWrite(30, TimeUnit.SECONDS).build();
    private final Cache<NetworkKey, Map<String, Object>> networks = Caffeine.newBuilder()
            .maximumSize(32).expireAfterWrite(30, TimeUnit.SECONDS).build();

    @Autowired
    public TagNetworkService(JdbcTemplate jdbc, EhTagTranslationService translations) {
        this(jdbc, translations, Ticker.systemTicker(), SNAPSHOT_CACHE_BYTES, SNAPSHOT_MAX_BYTES);
    }

    TagNetworkService(JdbcTemplate jdbc, EhTagTranslationService translations, Ticker ticker,
                      long snapshotCacheBytes, long snapshotMaxBytes) {
        this.jdbc = jdbc;
        this.translations = translations;
        this.snapshotMaxBytes = snapshotMaxBytes;
        this.snapshots = Caffeine.newBuilder().maximumWeight(snapshotCacheBytes)
                .weigher((String key, CatalogSnapshot snapshot) -> snapshot.estimatedBytes())
                .expireAfterWrite(SNAPSHOT_SECONDS, TimeUnit.SECONDS).ticker(ticker).build();
    }

    /** 按请求返回高频标签网络或完整目录快照，后续分页必须使用同一快照。 */
    public Map<String, Object> network(int limit, int minWeight, String keyword, int offset, int pageSize,
                                       String snapshotId) {
        String query = keyword(keyword);
        if (limit == 0) {
            // 完整目录的页码必须绑定同一快照，数据刷新或缓存过期时明确要求重载，不能悄悄漏标签。
            if (offset < 0) throw new IllegalArgumentException("标签分页位置不能小于 0");
            int size = Math.max(50, Math.min(1000, pageSize));
            String id = snapshotId == null ? "" : snapshotId.trim();
            CatalogSnapshot snapshot;
            if (id.isEmpty()) {
                if (offset != 0) throw new IllegalArgumentException("后续标签分页需要快照，请重新加载全部标签");
                id = UUID.randomUUID().toString();
                snapshot = createSnapshot(query, id);
            } else {
                if (id.length() > 64) throw new IllegalArgumentException("标签快照无效，请重新加载全部标签");
                snapshot = snapshots.getIfPresent(id);
                if (snapshot == null) throw new IllegalArgumentException("标签快照已过期或被释放，请重新加载全部标签");
                if (!snapshot.keyword().equals(query)) throw new IllegalArgumentException("查询条件与标签快照不一致，请重新加载全部标签");
            }
            List<SnapshotTag> tags = snapshot.tags();
            int start = Math.min(offset, tags.size()), end = (int) Math.min((long) start + size, tags.size());
            return Map.of("nodes", tags.subList(start, end).stream().map(SnapshotTag::node).toList(),
                    "links", List.of(), "total", tags.size(), "hasMore", end < tags.size(),
                    "nextOffset", end, "mode", "all", "snapshotId", id, "snapshotSeconds", SNAPSHOT_SECONDS);
        }
        int size = Math.max(8, Math.min(60, limit)), weight = Math.max(1, Math.min(100, minWeight));
        return networks.get(new NetworkKey(query, size, weight), this::topNetwork);
    }

    /** 只展开选中标签的直接共现关系，限制返回数量并报告真实关联总数。 */
    public Map<String, Object> neighbors(String tag, String keyword, int minWeight, int limit) {
        if (tag == null || tag.isBlank() || tag.length() > 200) throw new IllegalArgumentException("请选择有效标签");
        return neighborhoods.get(new NeighborhoodKey(keyword(keyword), tag.trim(),
                Math.max(1, Math.min(100, minWeight)), Math.max(10, Math.min(200, limit))), this::loadNeighbors);
    }

    /** 按作品范围统计标签出现次数，复用短期缓存减少重复聚合。 */
    private List<Map<String, Object>> catalog(String keyword) {
        return catalogs.get(keyword, key -> jdbc.queryForList(catalogSql(key), params(key)));
    }

    // 首包目录串行建立，避免多个请求同时把完整目录加载到内存。
    /** 流式读取标签目录，边读取边限制内存，并把名称、分类和译名固定在同一快照。 */
    private synchronized CatalogSnapshot createSnapshot(String query, String id) {
        Map<String, String> dictionary = dictionaries.get(0, ignored -> translations.getTranslationMap());
        List<SnapshotTag> tags = new ArrayList<>();
        long[] bytes = {256L + query.length() * 2L};
        jdbc.query(connection -> {
            var statement = connection.prepareStatement(catalogSql(query), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            // 让 MySQL 驱动逐行读取，边读边检查容量，而不是先在驱动里缓存整个结果。
            statement.setFetchSize(Integer.MIN_VALUE);
            Object[] parameters = params(query);
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            return statement;
        }, (RowCallbackHandler) row -> {
            String name = row.getString("name");
            String label = dictionary == null ? name : dictionary.getOrDefault(name.toLowerCase(Locale.ROOT), name);
            String namespace = name.contains(":") ? name.substring(0, name.indexOf(':')) : "misc";
            // 估算行对象、列表引用和字符串占用，提前拒绝过大的快照。
            bytes[0] += 192L + 2L * (name.length() + label.length() + namespace.length());
            if (bytes[0] > snapshotMaxBytes) {
                throw new IllegalArgumentException("标签目录超过单次快照容量，请缩小作品关键词后重试");
            }
            tags.add(new SnapshotTag(name, label, namespace, row.getLong("value")));
        });
        CatalogSnapshot snapshot = new CatalogSnapshot(query, List.copyOf(tags), (int) bytes[0]);
        // 快照中的顺序、总数和译名一起固定；后续分页只切片，不重新做全库聚合。
        snapshots.put(id, snapshot);
        snapshots.cleanUp();
        if (snapshots.getIfPresent(id) == null) {
            throw new IllegalArgumentException("标签快照缓存繁忙，请稍后重试或缩小查询范围");
        }
        return snapshot;
    }

    private static String catalogSql(String keyword) {
        return "SELECT jt.tag name, COUNT(DISTINCT g.gid) value FROM eh_galleries g " +
                "JOIN JSON_TABLE(g.tags,'$[*]' COLUMNS(tag VARCHAR(200) PATH '$')) jt " +
                "ON jt.tag IS NOT NULL AND jt.tag <> ''" + where(keyword) +
                " GROUP BY jt.tag ORDER BY value DESC, jt.tag ASC";
    }

    /** 仅在少量高频标签之间计算共现，限制连线数量。 */
    private Map<String, Object> topNetwork(NetworkKey key) {
        List<Map<String, Object>> catalog = catalog(key.keyword());
        List<Map<String, Object>> tags = catalog.subList(0, Math.min(key.limit(), catalog.size()));
        if (tags.isEmpty()) return Map.of("nodes", List.of(), "links", List.of(), "total", 0, "hasMore", false);
        List<String> names = tags.stream().map(row -> String.valueOf(row.get("name"))).toList();
        List<Object> args = new ArrayList<>(Arrays.asList(params(key.keyword())));
        args.addAll(names); args.addAll(names); args.add(key.minWeight());
        String placeholders = String.join(",", Collections.nCopies(names.size(), "?"));
        List<Map<String, Object>> links = jdbc.queryForList(
                "SELECT a.tag source, b.tag target, COUNT(DISTINCT g.gid) value FROM eh_galleries g " +
                        "JOIN JSON_TABLE(g.tags,'$[*]' COLUMNS(tag VARCHAR(200) PATH '$')) a " +
                        "JOIN JSON_TABLE(g.tags,'$[*]' COLUMNS(tag VARCHAR(200) PATH '$')) b ON a.tag < b.tag" +
                        where(key.keyword()) + " AND a.tag IN (" + placeholders + ") AND b.tag IN (" + placeholders + ")" +
                        " GROUP BY a.tag,b.tag HAVING COUNT(DISTINCT g.gid) >= ? ORDER BY value DESC LIMIT 240", args.toArray());
        return Map.of("nodes", tags.stream().map(this::node).toList(), "links", links, "total", catalog.size(), "mode", "top");
    }

    /** 先统计关联总数，再读取最强的有限关联，让界面说明是否还有未展示标签。 */
    private Map<String, Object> loadNeighbors(NeighborhoodKey key) {
        List<Object> args = new ArrayList<>(Arrays.asList(params(key.keyword())));
        args.add(key.tag()); args.add(key.tag()); args.add(key.minWeight());
        String grouped = " FROM eh_galleries g JOIN JSON_TABLE(g.tags,'$[*]' COLUMNS(tag VARCHAR(200) PATH '$')) jt " +
                "ON jt.tag IS NOT NULL" + where(key.keyword()) +
                " AND JSON_CONTAINS(g.tags,JSON_QUOTE(?)) AND jt.tag <> ?" +
                " GROUP BY jt.tag HAVING COUNT(DISTINCT g.gid) >= ?";
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT jt.tag" + grouped + ") neighbors", Long.class, args.toArray());
        List<Object> limitedArgs = new ArrayList<>(args);
        limitedArgs.add(key.limit());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT jt.tag name, COUNT(DISTINCT g.gid) value" + grouped + " ORDER BY value DESC,jt.tag ASC LIMIT ?", limitedArgs.toArray());
        List<Object> focusArgs = new ArrayList<>(Arrays.asList(params(key.keyword())));
        focusArgs.add(key.tag());
        Long focusedCount = jdbc.queryForObject("SELECT COUNT(*) FROM eh_galleries g" + where(key.keyword()) +
                " AND JSON_CONTAINS(g.tags,JSON_QUOTE(?))", Long.class, focusArgs.toArray());
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node(Map.of("name", key.tag(), "value", focusedCount == null ? 0 : focusedCount)));
        nodes.addAll(rows.stream().map(this::node).toList());
        List<Map<String, Object>> links = rows.stream().map(row -> Map.<String, Object>of(
                "source", key.tag(), "target", String.valueOf(row.get("name")), "value", row.get("value"))).toList();
        long total = count == null ? 0 : count;
        return Map.of("nodes", nodes, "links", links, "totalConnections", total, "truncated", total > rows.size());
    }

    private Map<String, Object> node(Map<String, Object> row) {
        String tag = String.valueOf(row.get("name"));
        // 全量目录使用本地翻译快照，避免每个标签都单独请求 Redis 或 AI。
        Map<String, String> dictionary = dictionaries.get(0, ignored -> translations.getTranslationMap());
        String label = dictionary == null ? tag : dictionary.getOrDefault(tag.toLowerCase(Locale.ROOT), tag);
        return Map.of("name", tag, "label", label,
                "namespace", tag.contains(":") ? tag.substring(0, tag.indexOf(':')) : "misc", "value", row.get("value"));
    }
    private static String keyword(String raw) {
        String query = raw == null ? "" : raw.trim();
        if (query.length() > 500) throw new IllegalArgumentException("作品关键词最多 500 个字符");
        return query;
    }
    private static String where(String keyword) {
        return " WHERE g.tags IS NOT NULL AND JSON_LENGTH(g.tags) > 0" + (keyword.isBlank() ? "" : " AND g.title LIKE ? ESCAPE '='");
    }
    private static Object[] params(String keyword) {
        return keyword.isBlank() ? new Object[0] : new Object[]{"%" + keyword.replace("=", "==").replace("%", "=%").replace("_", "=_") + "%"};
    }
    private record NetworkKey(String keyword, int limit, int minWeight) {}
    private record NeighborhoodKey(String keyword, String tag, int minWeight, int limit) {}
    private record CatalogSnapshot(String keyword, List<SnapshotTag> tags, int estimatedBytes) {}
    private record SnapshotTag(String name, String label, String namespace, long value) {
        Map<String, Object> node() {
            return Map.of("name", name, "label", label, "namespace", namespace, "value", value);
        }
    }
}
