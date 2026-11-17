package com.checker.rss;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable, independent collection placement. These jobs never change download state or start downloads. */
@Slf4j
@Service
public class RssCollectionService {
    private static final int MAX_ATTEMPTS = 5;
    private static final long POLL_DELAY = 60_000L;
    private static final String ACTIVE_HIT = """
            s.enabled=TRUE AND s.deleted=FALSE AND s.collection_enabled=TRUE
            AND s.collection_id IS NOT NULL AND i.revision=s.revision AND i.eligible=TRUE
            """;
    private static final String VALID_TASK = """
            EXISTS (SELECT 1 FROM rss_subscription s JOIN rss_item i ON i.subscription_id=s.id
              WHERE s.id=t.subscription_id AND s.revision=t.revision AND s.collection_id=t.collection_id
                AND i.gid=t.gid AND s.enabled=TRUE AND s.deleted=FALSE AND s.collection_enabled=TRUE
                AND i.revision=s.revision AND i.eligible=TRUE)
            """;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RssCollectionArchiveService archives;
    private final TaskExecutor executor;
    private final RssConfig config;
    private final AtomicBoolean running = new AtomicBoolean();

    record Target(String subscriptionId, int revision, long collectionId) {}
    record Gallery(String status, Long duplicateOf) {}
    record Membership(long collectionId, String signature) {}
    record Task(String subscriptionId, long gid, long collectionId, int revision, String status,
                int attempts, long updatedAt, String signature) {}
    record Prepared(Task task, String signature) {}
    record Decision(String status, String reason) {}

    private static final RowMapper<Task> TASK_ROW = (rs, row) -> new Task(
            rs.getString("subscription_id"), rs.getLong("gid"), rs.getLong("collection_id"),
            rs.getInt("revision"), rs.getString("status"), rs.getInt("attempts"),
            rs.getLong("updated_at"), rs.getString("placement_signature"));

    public RssCollectionService(JdbcTemplate db, PlatformTransactionManager manager,
                                RssCollectionArchiveService archives,
                                @Qualifier("backgroundTaskExecutor") TaskExecutor executor, RssConfig config) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.archives = archives;
        this.executor = executor;
        this.config = config;
    }

    /** Called before local-import placement resolution: database changes only, no storage or Komga calls. */
    public void assignForGallery(long gid) {
        if (!config.isEnabled()) return;
        tx.executeWithoutResult(status -> assign(gid));
    }

    private void assign(long gid) {
        List<Gallery> galleries = gallery(gid, true);
        if (galleries.isEmpty() || galleries.get(0).duplicateOf() != null || membership(gid) != null) return;
        List<Target> targets = db.query("""
                SELECT s.id,s.revision,s.collection_id FROM rss_item i
                JOIN rss_subscription s ON s.id=i.subscription_id
                WHERE i.gid=? AND
                """ + ACTIVE_HIT + " ORDER BY i.discovered_at,s.id LIMIT 1 FOR UPDATE",
                (rs, row) -> new Target(rs.getString(1), rs.getInt(2), rs.getLong(3)), gid);
        if (targets.isEmpty()) return;
        long collectionId = targets.get(0).collectionId();
        List<Long> collections = db.query("SELECT id FROM eh_gallery_collections WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getLong(1), collectionId);
        if (collections.isEmpty()) return;
        List<Integer> orders = db.queryForList(
                "SELECT sort_order FROM eh_gallery_collection_items WHERE collection_id=? ORDER BY sort_order DESC,gid DESC LIMIT 1 FOR UPDATE",
                Integer.class, collectionId);
        int nextOrder = orders.isEmpty() ? 0 : orders.get(0) + 1;
        int inserted = db.update("""
                INSERT IGNORE INTO eh_gallery_collection_items(collection_id,gid,sort_order,added_source)
                VALUES(?,?,?,'RSS')
                """, collectionId, gid, nextOrder);
        if (inserted > 0) db.update("UPDATE eh_gallery_collections SET updated_at=CURRENT_TIMESTAMP WHERE id=?", collectionId);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void schedule() {
        if (!config.isEnabled() || !running.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try { runOnce(); }
                catch (Exception failure) { log.warn("RSS 合集归档维护失败，将在下次恢复: {}", failure.getClass().getSimpleName()); }
                finally { running.set(false); }
            });
        } catch (RuntimeException rejected) {
            running.set(false);
            log.warn("RSS 合集归档后台队列暂不可用，将在下次重试");
        }
    }

    /** One connection owns the MySQL advisory lock until the batch finishes or the process disconnects. */
    void runOnce() {
        if (!config.isEnabled()) return;
        try (Connection connection = Objects.requireNonNull(db.getDataSource()).getConnection()) {
            String lockName = "rss-collection-" + Integer.toHexString(Objects.toString(connection.getCatalog(), "").hashCode());
            if (!lock(connection, "GET_LOCK", lockName)) return;
            try {
                enqueue();
                List<Task> tasks = db.query("SELECT t.* FROM rss_collection_task t WHERE " + VALID_TASK + """
                         AND t.status IN ('WAITING','WAITING_IMPORT','WAITING_KOMGA','RETRY','RUNNING')
                         AND t.next_attempt_at<=? ORDER BY t.next_attempt_at,t.updated_at,t.subscription_id,t.gid LIMIT 50
                        """, TASK_ROW, System.currentTimeMillis());
                for (Task task : tasks) {
                    if (!config.isEnabled()) break;
                    process(task);
                }
            } finally {
                if (!lock(connection, "RELEASE_LOCK", lockName)) log.warn("RSS 合集归档数据库锁已失效");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("RSS 合集归档数据库锁不可用", failure);
        }
    }

    private static boolean lock(Connection connection, String function, String name) throws SQLException {
        String sql = "GET_LOCK".equals(function) ? "SELECT GET_LOCK(?,0)" : "SELECT RELEASE_LOCK(?)";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (var result = statement.executeQuery()) { return result.next() && result.getInt(1) == 1; }
        }
    }

    /** Reconcile current settings even when the feed no longer contains the imported gallery. */
    void enqueue() {
        long now = System.currentTimeMillis();
        db.update("""
                INSERT IGNORE INTO rss_collection_task(subscription_id,gid,collection_id,revision,status,updated_at)
                SELECT s.id,i.gid,s.collection_id,s.revision,'WAITING',? FROM rss_item i
                JOIN rss_subscription s ON s.id=i.subscription_id WHERE
                """ + ACTIVE_HIT, now);
        // A later query revision may rediscover the same gallery/target. Old revisions never resume by themselves.
        db.update("""
                UPDATE rss_collection_task t JOIN rss_subscription s ON s.id=t.subscription_id
                JOIN rss_item i ON i.subscription_id=s.id AND i.gid=t.gid AND i.revision=s.revision
                SET t.revision=s.revision,t.status='WAITING',t.attempts=0,t.next_attempt_at=0,t.updated_at=?,t.last_error=NULL
                WHERE s.collection_id=t.collection_id AND
                """ + ACTIVE_HIT + " AND (t.revision<>s.revision OR t.status='CANCELLED')", now);
        db.update("""
                UPDATE rss_collection_task t SET t.status='CANCELLED',t.updated_at=?,t.last_error='订阅已暂停、删除或条件/目标已变更'
                WHERE t.status IN ('WAITING','WAITING_IMPORT','WAITING_KOMGA','RETRY','RUNNING') AND NOT
                """ + VALID_TASK, now);
        // Only layout changes reopen a completed job. A removed/moved membership is checked as a conflict below.
        db.update("""
                UPDATE rss_collection_task t LEFT JOIN eh_gallery_collection_items ci ON ci.gid=t.gid
                LEFT JOIN eh_gallery_collections c ON c.id=ci.collection_id
                SET t.status='WAITING',t.attempts=0,t.next_attempt_at=0,t.updated_at=?,t.last_error=NULL
                WHERE t.status='SYNCED' AND
                """ + VALID_TASK + """
                 AND (ci.gid IS NULL OR ci.collection_id<>t.collection_id OR t.placement_signature IS NULL
                      OR BINARY t.placement_signature<>BINARY CONCAT(c.id,':',c.name,':',ci.sort_order))
                """, now);
    }

    void process(Task candidate) {
        Prepared prepared = tx.execute(status -> prepare(candidate));
        if (prepared == null) return;
        Task task = prepared.task();
        try {
            // Recheck immediately before external effects; a paused/revised pending task must not move files.
            if (!current(task)) {
                finish(task, "CANCELLED", task.attempts(), 0, "订阅已暂停、删除或条件/目标已变更", prepared.signature());
                return;
            }
            boolean complete = archives.synchronize(task.gid(), task.collectionId());
            finish(task, complete ? "SYNCED" : "WAITING_KOMGA", 0,
                    complete ? 0 : System.currentTimeMillis() + POLL_DELAY, null, prepared.signature());
        } catch (Exception failure) {
            int attempts = task.attempts() + 1;
            finish(task, attempts >= MAX_ATTEMPTS ? "FAILED" : "RETRY", attempts,
                    System.currentTimeMillis() + retryDelay(attempts), safeError(failure), prepared.signature());
        }
    }

    private Prepared prepare(Task candidate) {
        List<Task> rows = db.query("""
                SELECT * FROM rss_collection_task WHERE subscription_id=? AND gid=? AND collection_id=? FOR UPDATE
                """, TASK_ROW, candidate.subscriptionId(), candidate.gid(), candidate.collectionId());
        if (rows.isEmpty()) return null;
        Task task = rows.get(0);
        if (task.revision() != candidate.revision() || task.updatedAt() != candidate.updatedAt()
                || !task.status().equals(candidate.status()) || !current(task)) return null;
        List<Gallery> galleries = gallery(task.gid(), true);
        Gallery gallery = galleries.isEmpty() ? null : galleries.get(0);
        Membership membership = membership(task.gid());
        boolean permitted = db.queryForObject("SELECT COUNT(*) FROM eh_download_permit WHERE gid=?", Long.class, task.gid()) > 0;
        Decision decision = readiness(gallery, membership, task.collectionId(), permitted, task.signature());
        if (decision == null && membership == null) {
            assign(task.gid());
            membership = membership(task.gid());
            decision = readiness(gallery, membership, task.collectionId(), permitted, task.signature());
            if (decision == null && membership == null) decision = new Decision("CONFLICT", "目标合集不存在或无法建立归属，保留原归档");
        }
        if (decision != null) {
            finish(task, decision.status(), task.attempts(), System.currentTimeMillis() + POLL_DELAY, decision.reason(), task.signature());
            return null;
        }
        long now = System.currentTimeMillis();
        int changed = db.update("""
                UPDATE rss_collection_task SET status='RUNNING',updated_at=?,next_attempt_at=?,last_error=NULL,placement_signature=?
                WHERE subscription_id=? AND gid=? AND collection_id=? AND revision=? AND status=? AND updated_at=?
                """, now, now + POLL_DELAY, membership.signature(), task.subscriptionId(), task.gid(), task.collectionId(),
                task.revision(), task.status(), task.updatedAt());
        return changed == 0 ? null : new Prepared(new Task(task.subscriptionId(), task.gid(), task.collectionId(), task.revision(),
                "RUNNING", task.attempts(), now, membership.signature()), membership.signature());
    }

    static Decision readiness(Gallery gallery, Membership membership, long target, boolean downloadPermit, String previousSignature) {
        if (gallery == null) return new Decision("WAITING_IMPORT", "等待画廊下载及 Komga 入库");
        if (gallery.duplicateOf() != null) return new Decision("CONFLICT", "画廊为其他版本的重复项，保留当前归档，请人工确认合集归属");
        if (membership != null && membership.collectionId() != target) return new Decision("CONFLICT", "画廊已有其他合集归属，保留现有归档");
        if (membership == null && previousSignature != null) return new Decision("CONFLICT", "画廊原合集归属已被移除，保留当前归档");
        if (!("IMPORTED".equals(gallery.status()) || "已入库".equals(gallery.status())) || downloadPermit) {
            return new Decision("WAITING_IMPORT", "等待入库完成及下载许可释放");
        }
        return null;
    }

    private List<Gallery> gallery(long gid, boolean forUpdate) {
        return db.query("SELECT download_status,duplicate_of_gid FROM eh_galleries WHERE gid=?" + (forUpdate ? " FOR UPDATE" : ""),
                (rs, row) -> new Gallery(rs.getString(1), rs.getObject(2) == null ? null : rs.getLong(2)), gid);
    }

    private Membership membership(long gid) {
        List<Membership> rows = db.query("""
                SELECT ci.collection_id,CONCAT(c.id,':',c.name,':',ci.sort_order) FROM eh_gallery_collection_items ci
                JOIN eh_gallery_collections c ON c.id=ci.collection_id WHERE ci.gid=?
                """, (rs, row) -> new Membership(rs.getLong(1), rs.getString(2)), gid);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean current(Task task) {
        return db.queryForObject("SELECT COUNT(*) FROM rss_collection_task t WHERE t.subscription_id=? AND t.gid=? AND t.collection_id=? AND t.revision=? AND " + VALID_TASK,
                Long.class, task.subscriptionId(), task.gid(), task.collectionId(), task.revision()) > 0;
    }

    private void finish(Task task, String state, int attempts, long next, String reason, String signature) {
        db.update("""
                UPDATE rss_collection_task SET status=?,attempts=?,next_attempt_at=?,updated_at=?,last_error=?,placement_signature=?
                WHERE subscription_id=? AND gid=? AND collection_id=? AND revision=? AND status=? AND updated_at=?
                """, state, attempts, next, System.currentTimeMillis(), reason, signature, task.subscriptionId(), task.gid(),
                task.collectionId(), task.revision(), task.status(), task.updatedAt());
    }

    static long retryDelay(int attempts) { return Math.min(3_600_000L, 60_000L << Math.min(Math.max(0, attempts - 1), 6)); }

    static String safeError(Exception error) {
        // Storage and HTTP exceptions may include credentials/paths. Persist a safe category, not raw remote text.
        return "合集归档失败（" + error.getClass().getSimpleName() + "），将按退避策略重试；达到 5 次后需手动重试";
    }
}
