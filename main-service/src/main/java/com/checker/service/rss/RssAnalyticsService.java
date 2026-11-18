package com.checker.service.rss;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RSS 订阅的多维统计：发现、入库、匹配既有画廊与归入合集的漏斗与分布。
 *
 * <p>只读取订阅、命中、下载与合集归位任务表，不修改任何状态；匹配率与归位率用于评估
 * “先匹配已有画廊再归集”这条链路的效果。</p>
 */
@Service
public class RssAnalyticsService {

    /** 归位任务状态的展示名称与顺序，供前端图表与明细表共用。 */
    private static final Map<String, String> PLACEMENT_LABELS = Map.ofEntries(
            Map.entry("SYNCED", "已归合集"),
            Map.entry("WAITING", "排队等待"),
            Map.entry("WAITING_IMPORT", "等待入库"),
            Map.entry("WAITING_KOMGA", "等待 Komga 确认"),
            Map.entry("WAITING_REVIEW", "待人工复核"),
            Map.entry("RUNNING", "整理中"),
            Map.entry("RETRY", "重试中"),
            Map.entry("FAILED", "整理失败"),
            Map.entry("CONFLICT", "归属冲突"),
            Map.entry("CANCELLED", "已取消"),
            Map.entry("SKIPPED_EXISTING", "保持原样"),
            Map.entry("SKIPPED_IGNORED", "已忽略"));

    private static final List<String> PLACEMENT_ORDER = List.of(
            "SYNCED", "WAITING", "WAITING_IMPORT", "WAITING_KOMGA", "WAITING_REVIEW",
            "RUNNING", "RETRY", "FAILED", "CONFLICT", "CANCELLED", "SKIPPED_EXISTING", "SKIPPED_IGNORED");

    private static final int TREND_DAYS = 14;

    private final JdbcTemplate db;

    public RssAnalyticsService(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * 汇总 RSS 数据：总览、漏斗、归位状态分布、按订阅明细与近两周趋势。
     */
    public Map<String, Object> analyze() {
        long since = System.currentTimeMillis() - TREND_DAYS * 86_400_000L;

        List<Map<String, Object>> subscriptions = db.queryForList("""
                SELECT s.id, COALESCE(JSON_UNQUOTE(JSON_EXTRACT(s.config_json,'$.name')), s.id) AS name,
                       s.enabled, s.collection_enabled, s.collection_id, s.revision,
                       c.name AS collection_name, s.last_success_at, s.last_error
                FROM rss_subscription s
                LEFT JOIN eh_gallery_collections c ON c.id=s.collection_id
                WHERE s.deleted=FALSE
                ORDER BY s.created_at DESC
                """);
        Map<String, Map<String, Object>> itemStats = indexBy(db.queryForList("""
                SELECT i.subscription_id,
                       COUNT(*) AS discovered,
                       SUM(CASE WHEN i.eligible THEN 1 ELSE 0 END) AS eligible,
                       SUM(CASE WHEN g.download_status IN ('IMPORTED','已入库') THEN 1 ELSE 0 END) AS imported,
                       SUM(CASE WHEN g.duplicate_of_gid IS NOT NULL THEN 1 ELSE 0 END) AS matched,
                       SUM(CASE WHEN g.download_status IN ('REVIEW_REQUIRED','待去重审核') THEN 1 ELSE 0 END) AS review_required
                FROM rss_item i
                JOIN rss_subscription s ON s.id=i.subscription_id AND s.revision=i.revision AND s.deleted=FALSE
                LEFT JOIN eh_galleries g ON g.gid=i.gid
                GROUP BY i.subscription_id
                """), "subscription_id");
        Map<String, Map<String, Long>> placement = placementBySubscription();
        Map<String, Map<String, Object>> latestPoll = indexBy(db.queryForList("""
                SELECT r.subscription_id, r.started_at, r.status, r.message
                FROM rss_poll_run r
                JOIN (SELECT subscription_id, MAX(started_at) AS latest FROM rss_poll_run GROUP BY subscription_id) x
                  ON x.subscription_id=r.subscription_id AND x.latest=r.started_at
                """), "subscription_id");
        Map<String, Long> unplaced = unplacedBySubscription();

        long discovered = 0;
        long eligible = 0;
        long imported = 0;
        long matched = 0;
        long placed = 0;
        long pendingReview = 0;
        long waitingPlacement = 0;
        long conflicts = 0;
        long failed = 0;
        long skipped = 0;
        long unplacedTotal = 0;
        long activeSubscriptions = 0;
        long collectionSubscriptions = 0;

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> subscription : subscriptions) {
            String id = text(subscription, "id");
            Map<String, Object> stats = itemStats.getOrDefault(id, Map.of());
            Map<String, Long> statuses = placement.getOrDefault(id, Map.of());
            Map<String, Object> poll = latestPoll.getOrDefault(id, Map.of());
            long subscriptionDiscovered = number(stats, "discovered");
            long subscriptionEligible = number(stats, "eligible");
            long subscriptionImported = number(stats, "imported");
            long subscriptionMatched = number(stats, "matched");
            long subscriptionPlaced = statuses.getOrDefault("SYNCED", 0L);
            long subscriptionPendingReview = statuses.getOrDefault("WAITING_REVIEW", 0L);
            long subscriptionConflicts = statuses.getOrDefault("CONFLICT", 0L);
            long subscriptionFailed = statuses.getOrDefault("FAILED", 0L);
            long subscriptionSkipped = statuses.getOrDefault("SKIPPED_EXISTING", 0L)
                    + statuses.getOrDefault("SKIPPED_IGNORED", 0L);
            long subscriptionWaiting = statuses.getOrDefault("WAITING", 0L)
                    + statuses.getOrDefault("WAITING_IMPORT", 0L)
                    + statuses.getOrDefault("WAITING_KOMGA", 0L)
                    + statuses.getOrDefault("RETRY", 0L)
                    + statuses.getOrDefault("RUNNING", 0L);

            boolean enabled = bool(subscription, "enabled");
            boolean collectionEnabled = bool(subscription, "collection_enabled");
            if (enabled) activeSubscriptions++;
            if (collectionEnabled) collectionSubscriptions++;
            discovered += subscriptionDiscovered;
            eligible += subscriptionEligible;
            imported += subscriptionImported;
            matched += subscriptionMatched;
            placed += subscriptionPlaced;
            pendingReview += subscriptionPendingReview;
            waitingPlacement += subscriptionWaiting;
            conflicts += subscriptionConflicts;
            failed += subscriptionFailed;
            skipped += subscriptionSkipped;
            unplacedTotal += unplaced.getOrDefault(id, 0L);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", text(subscription, "name"));
            row.put("enabled", enabled);
            row.put("collectionEnabled", collectionEnabled);
            row.put("collectionId", value(subscription, "collection_id"));
            row.put("collectionName", text(subscription, "collection_name"));
            row.put("revision", number(subscription, "revision"));
            row.put("discovered", subscriptionDiscovered);
            row.put("eligible", subscriptionEligible);
            row.put("imported", subscriptionImported);
            row.put("matched", subscriptionMatched);
            row.put("reviewRequired", number(stats, "review_required"));
            row.put("placed", subscriptionPlaced);
            row.put("pendingReview", subscriptionPendingReview);
            row.put("waiting", subscriptionWaiting);
            row.put("conflicts", subscriptionConflicts);
            row.put("failed", subscriptionFailed);
            row.put("skipped", subscriptionSkipped);
            row.put("unplaced", unplaced.getOrDefault(id, 0L));
            row.put("lastPollAt", value(poll, "started_at"));
            row.put("lastPollStatus", text(poll, "status"));
            row.put("lastPollMessage", text(poll, "message"));
            row.put("lastSuccessAt", value(subscription, "last_success_at"));
            row.put("lastError", text(subscription, "last_error"));
            rows.add(row);
        }

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("subscriptions", subscriptions.size());
        overview.put("activeSubscriptions", activeSubscriptions);
        overview.put("collectionSubscriptions", collectionSubscriptions);
        overview.put("discovered", discovered);
        overview.put("eligible", eligible);
        overview.put("imported", imported);
        overview.put("matched", matched);
        overview.put("placed", placed);
        overview.put("pendingReview", pendingReview);
        overview.put("waitingPlacement", waitingPlacement);
        overview.put("conflicts", conflicts);
        overview.put("failed", failed);
        overview.put("skipped", skipped);
        overview.put("unplaced", unplacedTotal);
        overview.put("matchRate", ratio(matched, imported));
        overview.put("placementRate", ratio(placed, imported));

        List<Map<String, Object>> funnel = List.of(
                funnelItem("发现", discovered, "discovered"),
                funnelItem("满足条件", eligible, "eligible"),
                funnelItem("已入库", imported, "imported"),
                funnelItem("匹配已有", matched, "matched"),
                funnelItem("已归合集", placed, "placed"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overview", overview);
        result.put("funnel", funnel);
        result.put("placement", placementDistribution(placement));
        result.put("subscriptions", rows);
        result.put("daily", dailyTrend(since));
        result.put("trendDays", TREND_DAYS);
        result.put("generatedAt", Instant.now().toString());
        return result;
    }

    /**
     * 按订阅统计归位任务状态，避免不同订阅的同类状态混在一起。
     */
    private Map<String, Map<String, Long>> placementBySubscription() {
        Map<String, Map<String, Long>> result = new HashMap<>();
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT t.subscription_id, t.status, COUNT(*) AS total
                FROM rss_collection_task t
                JOIN rss_subscription s ON s.id=t.subscription_id AND s.revision=t.revision AND s.deleted=FALSE
                WHERE s.collection_enabled=TRUE AND s.collection_id=t.collection_id
                GROUP BY t.subscription_id, t.status
                """);
        for (Map<String, Object> row : rows) {
            String id = text(row, "subscription_id");
            result.computeIfAbsent(id, key -> new HashMap<>()).merge(text(row, "status"), number(row, "total"), Long::sum);
        }
        return result;
    }

    /**
     * 统计已入库但仍未归入任何合集的订阅命中，用于识别“匹配后未归位”的缺口。
     */
    private Map<String, Long> unplacedBySubscription() {
        Map<String, Long> result = new HashMap<>();
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT i.subscription_id, COUNT(*) AS unplaced
                FROM rss_item i
                JOIN rss_subscription s ON s.id=i.subscription_id AND s.revision=i.revision
                     AND s.deleted=FALSE AND s.collection_enabled=TRUE
                JOIN eh_galleries g ON g.gid=i.gid
                LEFT JOIN eh_gallery_collection_items ci ON ci.gid=COALESCE(g.duplicate_of_gid,g.gid)
                WHERE g.download_status IN ('IMPORTED','已入库') AND ci.gid IS NULL
                GROUP BY i.subscription_id
                """);
        for (Map<String, Object> row : rows) {
            result.put(text(row, "subscription_id"), number(row, "unplaced"));
        }
        return result;
    }

    /**
     * 汇总所有订阅的归位状态分布，保持固定的展示顺序。
     */
    private List<Map<String, Object>> placementDistribution(Map<String, Map<String, Long>> placement) {
        Map<String, Long> totals = new LinkedHashMap<>();
        PLACEMENT_ORDER.forEach(status -> totals.put(status, 0L));
        placement.values().forEach(statuses -> statuses.forEach((status, count) ->
                totals.merge(status, count, Long::sum)));
        List<Map<String, Object>> result = new ArrayList<>();
        totals.forEach((status, count) -> {
            if (count == 0) return;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("status", status);
            item.put("name", PLACEMENT_LABELS.getOrDefault(status, status));
            item.put("value", count);
            result.add(item);
        });
        return result;
    }

    /**
     * 近两周按天统计发现、满足条件与归入合集的数量，缺口日期补零便于直接绘图。
     */
    private List<Map<String, Object>> dailyTrend(long since) {
        Map<String, Map<String, Object>> byDay = new LinkedHashMap<>();
        java.time.LocalDate start = java.time.Instant.ofEpochMilli(since)
                .atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate();
        for (int offset = 0; offset <= TREND_DAYS; offset++) {
            String day = start.plusDays(offset).toString();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", day);
            point.put("discovered", 0L);
            point.put("eligible", 0L);
            point.put("placed", 0L);
            byDay.put(day, point);
        }
        for (Map<String, Object> row : db.queryForList("""
                SELECT DATE(FROM_UNIXTIME(i.discovered_at/1000)) AS day, COUNT(*) AS discovered,
                       SUM(CASE WHEN i.eligible THEN 1 ELSE 0 END) AS eligible
                FROM rss_item i
                JOIN rss_subscription s ON s.id=i.subscription_id AND s.revision=i.revision AND s.deleted=FALSE
                WHERE i.discovered_at >= ?
                GROUP BY day ORDER BY day
                """, since)) {
            Map<String, Object> point = byDay.get(String.valueOf(value(row, "day")));
            if (point == null) continue;
            point.put("discovered", number(row, "discovered"));
            point.put("eligible", number(row, "eligible"));
        }
        for (Map<String, Object> row : db.queryForList("""
                SELECT DATE(FROM_UNIXTIME(t.updated_at/1000)) AS day, COUNT(*) AS placed
                FROM rss_collection_task t
                WHERE t.status='SYNCED' AND t.updated_at >= ?
                GROUP BY day ORDER BY day
                """, since)) {
            Map<String, Object> point = byDay.get(String.valueOf(value(row, "day")));
            if (point == null) continue;
            point.put("placed", number(row, "placed"));
        }
        return new ArrayList<>(byDay.values());
    }

    private static Map<String, Object> funnelItem(String name, long value, String key) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("value", value);
        item.put("key", key);
        return item;
    }

    private static double ratio(long part, long total) {
        return total <= 0 ? 0D : Math.round(part * 1000D / total) / 1000D;
    }

    private static Map<String, Map<String, Object>> indexBy(List<Map<String, Object>> rows, String key) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(text(row, key), row);
        }
        return result;
    }

    private static Object value(Map<String, Object> row, String key) {
        if (row == null) return null;
        if (row.containsKey(key)) return row.get(key);
        return row.get(key.toLowerCase(Locale.ROOT));
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean bool(Map<String, Object> row, String key) {
        Object value = value(row, key);
        if (value instanceof Boolean flag) return flag;
        if (value instanceof Number number) return number.longValue() != 0;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
