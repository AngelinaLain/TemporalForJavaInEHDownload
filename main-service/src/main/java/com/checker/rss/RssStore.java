package com.checker.rss;

import com.checker.common.EhSearchQueryBuilder;
import com.checker.dto.RssSearchItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.sql.ResultSetMetaData;
import java.util.*;

/** Durable subscription inbox and transactional outbox. No network calls inside transactions. */
@Service
public class RssStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final RssConfig config;
    public RssStore(JdbcTemplate db, ObjectMapper json, PlatformTransactionManager manager, RssConfig config) {
        this.db = db; this.json = json; this.tx = new TransactionTemplate(manager); this.config = config;
    }
    public record Snapshot(String id, int revision, RssSubscriptionRequest request, long baseline,
                           Long coverage, String cursor, Long scanStarted, Long scanBefore, int scanPages, Long lastBackfill, Long lastSuccess) {}
    public record Batch(String id, String workflowId, String runId, String status, long createdAt, List<String> urls) {}
    public record Task(long gid, int attempts, String status) {}

    static final RowMapper<Map<String, Object>> ROW = (rs, n) -> {
        Map<String, Object> map = new LinkedHashMap<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String label = meta.getColumnLabel(i).toLowerCase(Locale.ROOT);
            StringBuilder camel = new StringBuilder(); boolean upper = false;
            for (char c : label.toCharArray()) {
                if (c == '_') upper = true;
                else { camel.append(upper ? Character.toUpperCase(c) : c); upper = false; }
            }
            map.put(camel.toString(), rs.getObject(i));
        }
        return map;
    };
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("订阅条件无法保存", e); }
    }
    private RssSubscriptionRequest decode(String value) {
        try { return json.readValue(value, RssSubscriptionRequest.class); } catch (Exception e) { throw new IllegalStateException("订阅条件损坏", e); }
    }
    public List<Map<String, Object>> list() {
        var rows = db.query("SELECT * FROM rss_subscription WHERE deleted=FALSE ORDER BY created_at DESC", ROW);
        for (var row : rows) {
            var request = decode((String) row.remove("configJson"));
            row.put("config", request);
            row.put("name", request.getName());
            row.put("route", EhSearchQueryBuilder.rssRoute(request.getSearchOptions()));
            row.put("counts", db.query("SELECT COALESCE(t.status,CASE WHEN i.eligible=TRUE THEN 'COLLECTED' ELSE 'BASELINE' END) status,COUNT(*) total FROM rss_item i LEFT JOIN rss_download_task t ON t.gid=i.gid WHERE i.subscription_id=? AND i.revision=? GROUP BY COALESCE(t.status,CASE WHEN i.eligible=TRUE THEN 'COLLECTED' ELSE 'BASELINE' END)", ROW, row.get("id"), row.get("revision")));
        }
        return rows;
    }
    public String save(String id, RssSubscriptionRequest request) {
        request.validate();
        String key = id == null ? UUID.randomUUID().toString() : id;
        tx.executeWithoutResult(status -> {
            long now = System.currentTimeMillis();
            long baseline = (now / 60000) * 60000 - (request.getInitialMode().equals("CURRENT") ? request.getHistoryDays() * 86400000L : 0);
            if (id == null) {
                db.update("INSERT INTO rss_subscription(id,config_json,enabled,auto_download,baseline_at,next_poll_at,created_at) VALUES(?,?,?,?,?,?,?)", key, encode(request), request.isEnabled(), request.isAutoDownload(), baseline, now, now);
            } else {
                var rows = db.query("SELECT * FROM rss_subscription WHERE id=? AND deleted=FALSE FOR UPDATE", ROW, id);
                if (rows.isEmpty()) throw new IllegalArgumentException("订阅不存在");
                RssSubscriptionRequest previous = decode((String) rows.get(0).get("configJson"));
                boolean changed = !EhSearchQueryBuilder.query(previous.getSearchOptions()).equals(EhSearchQueryBuilder.query(request.getSearchOptions()))
                        || !previous.getInitialMode().equals(request.getInitialMode()) || previous.getHistoryDays() != request.getHistoryDays();
                db.update("UPDATE rss_subscription SET config_json=?,enabled=?,auto_download=?,next_poll_at=? WHERE id=?", encode(request), request.isEnabled(), request.isAutoDownload(), now, id);
                if (changed) db.update("UPDATE rss_subscription SET revision=revision+1,baseline_at=?,coverage_at=NULL,last_backfill_at=NULL,scan_cursor=NULL,scan_started_at=NULL,scan_before_at=NULL,scan_pages=0,last_error=NULL,error_count=0 WHERE id=?", baseline, id);
            }
            db.update("INSERT IGNORE INTO rss_subscription_revision(subscription_id,revision,config_json,baseline_at) SELECT id,revision,config_json,baseline_at FROM rss_subscription WHERE id=?",key);
        });
        return key;
    }
    public void enable(String id, boolean enabled) {
        require(id);
        db.update("UPDATE rss_subscription SET enabled=?,next_poll_at=? WHERE id=?", enabled, System.currentTimeMillis(), id);
    }
    public void delete(String id) { require(id); db.update("UPDATE rss_subscription SET deleted=TRUE,enabled=FALSE WHERE id=?", id); }
    public void requestPoll(String id) { require(id); db.update("UPDATE rss_subscription SET next_poll_at=0,last_backfill_at=NULL WHERE id=? AND enabled=TRUE", id); }
    private void require(String id) {
        if (db.queryForObject("SELECT COUNT(*) FROM rss_subscription WHERE id=? AND deleted=FALSE", Long.class, id) == 0) throw new IllegalArgumentException("订阅不存在");
    }
    public List<Map<String,Object>> due() {
        return db.query("SELECT id,revision FROM rss_subscription WHERE enabled=TRUE AND deleted=FALSE AND next_poll_at<=? ORDER BY next_poll_at LIMIT 30", ROW, System.currentTimeMillis());
    }
    public Snapshot load(String id, int revision) {
        var rows = db.query("SELECT * FROM rss_subscription WHERE id=? AND revision=? AND enabled=TRUE AND deleted=FALSE", ROW, id, revision);
        if (rows.isEmpty()) return null;
        var r = rows.get(0);
        return new Snapshot(id, revision, decode((String) r.get("configJson")), number(r,"baselineAt"), nullable(r,"coverageAt"), (String) r.get("scanCursor"), nullable(r,"scanStartedAt"), nullable(r,"scanBeforeAt"), (int) number(r,"scanPages"), nullable(r,"lastBackfillAt"), nullable(r,"lastSuccessAt"));
    }
    private static long number(Map<String,Object> row,String key) { return ((Number) row.get(key)).longValue(); }
    private static Long nullable(Map<String,Object> row,String key) { return row.get(key) == null ? null : number(row,key); }
    public void beginRun(String run, Snapshot s) {
        db.update("INSERT IGNORE INTO rss_poll_run(id,subscription_id,revision,started_at,status) VALUES(?,?,?,?, 'RUNNING')", run, s.id(), s.revision(), System.currentTimeMillis());
    }
    public int record(Snapshot s, List<RssSearchItem> items) {
        return tx.execute(status -> {
            // Lock and recheck revision so an old workflow cannot overwrite a newly edited subscription.
            var current = db.query("SELECT revision,enabled,deleted FROM rss_subscription WHERE id=? FOR UPDATE", ROW, s.id());
            if (current.isEmpty() || number(current.get(0),"revision") != s.revision() || !Boolean.TRUE.equals(current.get(0).get("enabled")) || Boolean.TRUE.equals(current.get(0).get("deleted"))) return 0;
            int inserted = 0; long now = System.currentTimeMillis();
            for (RssSearchItem item : items) {
                boolean eligible = item.publishedAt() >= s.baseline();
                inserted += db.update("INSERT IGNORE INTO rss_item(subscription_id,revision,gid,token,url,title,published_at,discovered_at,eligible) VALUES(?,?,?,?,?,?,?,?,?)", s.id(),s.revision(),item.gid(),item.token(),item.url(),item.title(),item.publishedAt(),now,eligible);
            }
            return inserted;
        });
    }
    public Snapshot beginScan(Snapshot s) {
        long now = System.currentTimeMillis();
        long before = s.coverage() == null ? s.baseline() : Math.max(s.baseline(), s.coverage() - 3600000L);
        db.update("UPDATE rss_subscription SET scan_started_at=?,scan_before_at=?,scan_cursor=NULL,scan_pages=0 WHERE id=? AND revision=? AND scan_started_at IS NULL AND enabled=TRUE AND deleted=FALSE", now,before,s.id(),s.revision());
        return load(s.id(),s.revision());
    }
    public void checkpoint(Snapshot s, String next, boolean complete, int pages) {
        if (complete) {
            db.update("UPDATE rss_subscription SET coverage_at=scan_started_at,last_backfill_at=?,scan_started_at=NULL,scan_before_at=NULL,scan_cursor=NULL,scan_pages=0 WHERE id=? AND revision=? AND scan_started_at=?", System.currentTimeMillis(), s.id(),s.revision(),s.scanStarted());
        } else {
            db.update("UPDATE rss_subscription SET scan_cursor=?,scan_pages=? WHERE id=? AND revision=? AND scan_started_at=? AND scan_pages<=?",next,pages,s.id(),s.revision(),s.scanStarted(),pages);
        }
    }
    public void finish(String run, Snapshot s, int count, int pages, String error) {
        tx.executeWithoutResult(status -> {
            long now = System.currentTimeMillis();
            int finished=db.update("UPDATE rss_poll_run SET finished_at=?,status=?,discovered_count=?,pages=?,message=? WHERE id=? AND finished_at IS NULL",now,error == null ? "SUCCESS" : "ERROR",count,pages,shortError(error),run);
            if (finished==0) return;
            if (error == null) db.update("UPDATE rss_subscription SET next_poll_at=?,last_success_at=?,last_error=NULL,error_count=0 WHERE id=? AND revision=?",now+s.request().getIntervalMinutes()*60000L,now,s.id(),s.revision());
            else db.update("UPDATE rss_subscription SET next_poll_at=?,last_error=?,error_count=error_count+1 WHERE id=? AND revision=?",now+Math.max(5,s.request().getIntervalMinutes())*60000L,shortError(error),s.id(),s.revision());
        });
    }
    static String shortError(String error) { return error == null ? null : error.substring(0, Math.min(1900,error.length())); }

    public Map<String,Object> items(String id, int page, int size) {
        require(id); int limit = Math.max(1,Math.min(100,size)); int offset = (Math.max(1,page)-1)*limit;
        var rows = db.query("SELECT i.*,r.config_json condition_snapshot,COALESCE(t.status,CASE WHEN i.eligible=TRUE THEN 'COLLECTED' ELSE 'BASELINE' END) status,t.attempts,COALESCE(t.last_error,b.last_error) last_error,b.workflow_id,g.download_status,g.duplicate_of_gid FROM rss_item i LEFT JOIN rss_subscription_revision r ON r.subscription_id=i.subscription_id AND r.revision=i.revision LEFT JOIN rss_download_task t ON i.gid=t.gid LEFT JOIN rss_batch b ON t.batch_id=b.id LEFT JOIN eh_galleries g ON g.gid=i.gid WHERE i.subscription_id=? ORDER BY i.discovered_at DESC,i.gid DESC LIMIT ? OFFSET ?",ROW,id,limit,offset);
        for (var row:rows) {
            String snapshot=(String)row.remove("conditionSnapshot");
            row.put("searchQuery",snapshot==null ? "" : decode(snapshot).getSearchOptions().getKeyword());
        }
        return Map.of("records",rows,"total",db.queryForObject("SELECT COUNT(*) FROM rss_item WHERE subscription_id=?",Long.class,id));
    }
    public List<Map<String,Object>> runs(String id) { require(id); return db.query("SELECT * FROM rss_poll_run WHERE subscription_id=? ORDER BY started_at DESC LIMIT 100",ROW,id); }

    public void enqueue() {
        long now = System.currentTimeMillis();
        db.update("INSERT IGNORE INTO rss_download_task(gid,url,status,next_attempt_at,updated_at) SELECT i.gid,MIN(i.url),'PENDING',?,? FROM rss_item i JOIN rss_subscription s ON s.id=i.subscription_id LEFT JOIN rss_download_task t ON t.gid=i.gid WHERE t.gid IS NULL AND i.eligible=TRUE AND s.enabled=TRUE AND s.deleted=FALSE AND s.auto_download=TRUE GROUP BY i.gid",now,now);
    }
    public Batch prepareBatch() {
        return tx.execute(status -> {
            db.queryForObject("SELECT id FROM rss_dispatch_lock WHERE id=1 FOR UPDATE",Integer.class);
            Long active = db.queryForObject("SELECT COUNT(*) FROM rss_batch WHERE status IN ('DISPATCHING','RUNNING')",Long.class);
            if (active >= config.getMaxActiveBatches()) return null;
            long now = System.currentTimeMillis();
            var rows = db.query("SELECT t.gid,t.url FROM rss_download_task t WHERE t.status IN ('PENDING','RETRY') AND t.next_attempt_at<=? AND EXISTS (SELECT 1 FROM rss_item i JOIN rss_subscription s ON i.subscription_id=s.id WHERE i.gid=t.gid AND i.eligible=TRUE AND s.enabled=TRUE AND s.deleted=FALSE AND s.auto_download=TRUE) ORDER BY t.next_attempt_at,t.gid LIMIT ? FOR UPDATE",ROW,now,Math.max(1,Math.min(25,config.getBatchSize())));
            if (rows.isEmpty()) return null;
            String id = UUID.randomUUID().toString(), workflow = "rss-batch-"+id;
            db.update("INSERT INTO rss_batch(id,workflow_id,status,created_at) VALUES(?,?,'DISPATCHING',?)",id,workflow,now);
            for (var row : rows) db.update("UPDATE rss_download_task SET status='QUEUED',batch_id=?,attempts=attempts+1,updated_at=?,last_error=NULL WHERE gid=?",id,now,row.get("gid"));
            return new Batch(id,workflow,null,"DISPATCHING",now,rows.stream().map(r -> (String) r.get("url")).toList());
        });
    }
    public List<Batch> batches() {
        return db.query("SELECT * FROM rss_batch WHERE status IN ('DISPATCHING','RUNNING') ORDER BY created_at LIMIT 100",ROW).stream().map(r -> new Batch((String)r.get("id"),(String)r.get("workflowId"),(String)r.get("runId"),(String)r.get("status"),number(r,"createdAt"),db.query("SELECT url FROM rss_download_task WHERE batch_id=? ORDER BY gid",(rs,n)->rs.getString(1),r.get("id")))).toList();
    }
    public void started(Batch b,String run) { db.update("UPDATE rss_batch SET status='RUNNING',run_id=?,last_error=NULL WHERE id=?",run,b.id()); }
    public void batchError(Batch b,String error) { db.update("UPDATE rss_batch SET last_error=? WHERE id=?",shortError(error),b.id()); }
    public List<Task> tasks(Batch b) { return db.query("SELECT gid,attempts,status FROM rss_download_task WHERE batch_id=?",(rs,n)->new Task(rs.getLong(1),rs.getInt(2),rs.getString(3)),b.id()); }
    public void taskState(Task task,String state,String error) {
        db.update("UPDATE rss_download_task SET status=?,last_error=?,updated_at=? WHERE gid=? AND attempts=? AND status=?",state,shortError(error),System.currentTimeMillis(),task.gid(),task.attempts(),task.status());
    }
    public void retryTask(Task task,String reason) {
        long now = System.currentTimeMillis();
        db.update("UPDATE rss_download_task SET status=?,next_attempt_at=?,updated_at=?,last_error=? WHERE gid=? AND attempts=? AND status=?", task.attempts() >= config.getMaxAttempts() ? "FAILED" : "RETRY",now+Math.min(360,5*(1L << Math.min(task.attempts(),6)))*60000L,now,shortError(reason),task.gid(),task.attempts(),task.status());
    }
    public void finishBatch(Batch b) { db.update("UPDATE rss_batch SET status='DONE' WHERE id=?",b.id()); }
    public List<Task> heldTasks() { return db.query("SELECT gid,attempts,status FROM rss_download_task WHERE status IN ('REVIEW_REQUIRED','WAITING_EXISTING') ORDER BY updated_at LIMIT 100",(rs,n)->new Task(rs.getLong(1),rs.getInt(2),rs.getString(3))); }
    public void retry(String id) {
        require(id);
        tx.executeWithoutResult(status -> {
            db.update("UPDATE eh_galleries SET download_status='未下载' WHERE download_status IN ('BLOCKED','阻断') AND gid IN (SELECT t.gid FROM rss_download_task t JOIN rss_item i ON t.gid=i.gid WHERE i.subscription_id=? AND t.status='BLOCKED') AND gid NOT IN (SELECT gid FROM eh_download_permit)",id);
            db.update("UPDATE rss_download_task SET status='PENDING',attempts=0,next_attempt_at=0,last_error=NULL WHERE status IN ('FAILED','RETRY','BLOCKED') AND gid IN (SELECT gid FROM rss_item WHERE subscription_id=?)",id);
        });
    }
}
