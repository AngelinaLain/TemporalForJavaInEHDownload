package com.checker.service;

import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Reconciles stale database IDs without writing Komga, gallery state, or physical archives. */
@Service
public class KomgaIdRefreshService {
    private static final int BATCH_SIZE = 200;
    private static final int RESULT_LIMIT = 200;
    private static final Pattern GID_PREFIX = Pattern.compile("^\\[(\\d+)](?:\\s|\\.|$)");
    private final EhGalleriesMapper galleries;
    private final KomgaMediaClient komga;
    private final GalleryMediaService previews;
    private final TaskExecutor executor;
    private volatile Job current = new Job();

    public KomgaIdRefreshService(EhGalleriesMapper galleries, KomgaMediaClient komga,
                                 GalleryMediaService previews,
                                 @Qualifier("backgroundTaskExecutor") TaskExecutor executor) {
        this.galleries = galleries;
        this.komga = komga;
        this.previews = previews;
        this.executor = executor;
    }

    public synchronized Map<String, Object> start(List<Long> requestedGids) {
        List<Long> gids = normalizeGids(requestedGids);
        if (!komga.configured()) throw new IllegalArgumentException("请先配置 Komga 地址、API Key 和目标图库");
        if (current.running) throw new IllegalStateException("Komga 关联 ID 刷新正在运行");
        Job job = new Job();
        job.jobId = UUID.randomUUID().toString(); job.running = true; job.status = "INDEXING"; job.startedAt = Instant.now();
        current = job;
        try { executor.execute(() -> run(job, gids)); }
        catch (RuntimeException rejected) {
            synchronized (job) {
                job.running = false; job.status = "FAILED"; job.finishedAt = Instant.now();
                job.lastError = "后台任务队列暂不可用，请稍后重试";
            }
            throw new IllegalStateException(job.lastError);
        }
        return snapshot(job);
    }

    public Map<String, Object> status() { return snapshot(current); }

    public Map<String, Object> cancel() { return cancel(null); }

    public synchronized Map<String, Object> cancel(String expectedJobId) {
        Job job = current;
        if (expectedJobId != null && !expectedJobId.equals(job.jobId))
            throw new IllegalStateException("显示的任务已变化，请刷新任务状态后重试");
        if (job.running) job.cancelled.set(true);
        return snapshot(job);
    }

    private void run(Job job, List<Long> gids) {
        try {
            Map<String, Object> bounds = galleries.getKomgaIdRefreshBounds(gids);
            long maxGid = numeric(bounds, "maxGid"), total = numeric(bounds, "total");
            synchronized (job) { job.total = total; }
            checkCancelled(job);
            if (maxGid > 0 && total > 0) {
                Index index = new Index(komga.librarySnapshot(job.cancelled::get));
                synchronized (job) { job.status = "RUNNING"; }
                long after = 0;
                while (after < maxGid) {
                    checkCancelled(job);
                    List<EhGalleriesEntity> batch = galleries.getKomgaIdRefreshBatch(after, maxGid, gids, BATCH_SIZE);
                    if (batch == null || batch.isEmpty()) break;
                    for (EhGalleriesEntity gallery : batch) {
                        checkCancelled(job);
                        long gid = gallery.getGid();
                        if (gid <= after || gid > maxGid) throw new IllegalStateException("数据库返回的画廊游标顺序无效");
                        after = gid;
                        synchronized (job) { job.currentGid = gid; }
                        process(job, gallery, index);
                    }
                }
            }
            synchronized (job) { job.status = job.cancelled.get() ? "CANCELLED" : "COMPLETED"; }
        } catch (CancellationException cancelled) {
            synchronized (job) { job.status = "CANCELLED"; }
        } catch (Exception failure) {
            synchronized (job) { job.status = "FAILED"; job.lastError = safeMessage(failure); }
        } finally {
            synchronized (job) { job.running = false; job.currentGid = null; job.finishedAt = Instant.now(); }
        }
    }

    private void process(Job job, EhGalleriesEntity gallery, Index index) {
        String oldId = gallery.getKomgaBookId(), nextId = null, resultStatus, message;
        try {
            String filename = basename(gallery.getFilename());
            var expectedGid = GID_PREFIX.matcher(filename);
            if (expectedGid.find() && !expectedGid.group(1).equals(gallery.getGid().toString())) {
                resultStatus = "CONFLICT"; message = "数据库文件名与画廊 GID 不一致，已保留原关联";
            } else if (oldId != null && index.byId.containsKey(oldId)) {
                if (index.matches(index.byId.get(oldId), gallery.getGid(), filename)) {
                    resultStatus = "UNCHANGED"; message = "当前 Komga 关联仍有效"; nextId = oldId;
                } else {
                    resultStatus = "CONFLICT"; message = "当前 ID 存在但物理文件身份不符，已保留原关联";
                }
            } else {
                Set<String> candidates = index.candidates(gallery.getGid(), filename);
                if (candidates.isEmpty()) {
                    resultStatus = "NOT_FOUND"; message = "未找到物理 GID 或文件名匹配的书籍，已保留原关联";
                } else if (candidates.size() > 1) {
                    resultStatus = "AMBIGUOUS"; message = "存在多个物理归档候选，已保留原关联";
                } else {
                    nextId = candidates.iterator().next();
                    checkCancelled(job);
                    int updated = galleries.compareAndSetKomgaBookId(gallery.getGid(), oldId, nextId,
                            gallery.getFilename(), gallery.getStoragePath());
                    if (updated == 1) {
                        resultStatus = "UPDATED"; message = "数据库中的 Komga 关联 ID 已更新";
                        try { previews.invalidate(gallery.getGid()); }
                        catch (RuntimeException cacheFailure) { message = "数据库关联 ID 已更新；预览缓存将自动过期"; }
                    } else {
                        resultStatus = "CONFLICT"; message = "记录已被其他任务修改，已跳过覆盖";
                    }
                }
            }
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (Exception failure) { resultStatus = "FAILED"; message = "更新数据库关联失败，请稍后重试"; }
        synchronized (job) {
            job.processed++;
            switch (resultStatus) {
                case "UPDATED" -> job.updated++;
                case "UNCHANGED" -> job.unchanged++;
                case "NOT_FOUND" -> job.notFound++;
                case "AMBIGUOUS" -> job.ambiguous++;
                case "CONFLICT" -> job.conflicts++;
                default -> job.failed++;
            }
            // Valid unchanged rows need no table entry; every actionable result remains counted.
            if (!"UNCHANGED".equals(resultStatus)) {
                job.resultsTotal++;
                if (job.results.size() < RESULT_LIMIT) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("gid", gallery.getGid()); result.put("title", truncate(gallery.getTitle(), 200));
                    result.put("oldId", oldId); result.put("newId", nextId); result.put("status", resultStatus); result.put("message", message);
                    job.results.add(result);
                }
            }
        }
    }

    private static List<Long> normalizeGids(List<Long> requested) {
        if (requested == null || requested.isEmpty()) return List.of();
        if (requested.size() > 1000) throw new IllegalArgumentException("一次最多指定 1000 个 GID");
        if (requested.stream().anyMatch(gid -> gid == null || gid <= 0)) throw new IllegalArgumentException("GID 必须是正整数");
        return List.copyOf(new LinkedHashSet<>(requested));
    }

    private static void checkCancelled(Job job) { if (job.cancelled.get()) throw new CancellationException(); }
    private static long numeric(Map<String, Object> row, String key) {
        if (row == null) return 0;
        return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue).filter(Number.class::isInstance).map(Number.class::cast).mapToLong(Number::longValue).findFirst().orElse(0);
    }
    private static String truncate(String value, int maximum) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), maximum));
    }
    private static String safeMessage(Exception failure) {
        // SQL/connection exception text can contain internal addresses or statement parameters.
        return failure instanceof java.io.IOException && failure.getMessage() != null
                ? truncate(failure.getMessage(), 240) : "刷新任务失败，请检查数据库和 Komga 服务后重试";
    }
    private static String basename(String value) {
        if (value == null) return "";
        String normalized = value.replace('\\', '/').trim();
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }
    private static String normalizedName(String value) { return basename(value).replaceFirst("(?i)\\.(cbz|zip)$", "").toLowerCase(Locale.ROOT); }

    private static Map<String, Object> snapshot(Job job) {
        synchronized (job) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("jobId", job.jobId); result.put("running", job.running); result.put("status", job.status);
            result.put("total", job.total); result.put("processed", job.processed); result.put("updated", job.updated);
            result.put("unchanged", job.unchanged); result.put("notFound", job.notFound); result.put("ambiguous", job.ambiguous);
            result.put("conflicts", job.conflicts); result.put("failed", job.failed); result.put("currentGid", job.currentGid);
            result.put("cancelRequested", job.cancelled.get()); result.put("startedAt", job.startedAt); result.put("finishedAt", job.finishedAt);
            result.put("lastError", job.lastError); result.put("results", job.results.stream().map(LinkedHashMap::new).toList());
            result.put("resultsTotal", job.resultsTotal); result.put("resultsTruncated", job.resultsTotal > job.results.size());
            return result;
        }
    }

    private static final class Index {
        final Map<String, KomgaMediaClient.LibraryBook> byId = new HashMap<>();
        final Map<String, Set<String>> byGid = new HashMap<>(), byFilename = new HashMap<>();
        final Map<String, Set<String>> physicalGids = new HashMap<>();
        Index(KomgaMediaClient.LibrarySnapshot snapshot) {
            for (KomgaMediaClient.LibraryBook book : snapshot.books()) {
                if (byId.putIfAbsent(book.bookId(), book) != null) throw new IllegalArgumentException("Komga 图库快照包含重复书籍 ID");
                Set<String> gids = new LinkedHashSet<>();
                for (String filename : book.filenames()) {
                    var gid = GID_PREFIX.matcher(filename);
                    if (gid.find()) gids.add(gid.group(1));
                }
                physicalGids.put(book.bookId(), Set.copyOf(gids));
                if (gids.size() == 1) {
                    byGid.computeIfAbsent(gids.iterator().next(), ignored -> new LinkedHashSet<>()).add(book.bookId());
                } else if (gids.isEmpty()) {
                    // An explicit different GID must never be overridden by a generic name field.
                    for (String filename : book.filenames()) if (!normalizedName(filename).isBlank())
                        byFilename.computeIfAbsent(normalizedName(filename), ignored -> new LinkedHashSet<>()).add(book.bookId());
                }
            }
        }
        Set<String> candidates(long gid, String filename) {
            Set<String> direct = byGid.get(String.valueOf(gid));
            return direct != null ? direct : filename.isBlank() ? Set.of() : byFilename.getOrDefault(normalizedName(filename), Set.of());
        }
        boolean matches(KomgaMediaClient.LibraryBook book, long gid, String filename) {
            Set<String> gids = physicalGids.get(book.bookId());
            if (!gids.isEmpty()) return gids.size() == 1 && gids.contains(String.valueOf(gid));
            for (String physical : book.filenames()) {
                if (!filename.isBlank() && Objects.equals(normalizedName(physical), normalizedName(filename))) return true;
            }
            return false;
        }
    }

    private static final class Job {
        String jobId, lastError;
        volatile boolean running;
        String status = "IDLE";
        long total, processed, updated, unchanged, notFound, ambiguous, conflicts, failed, resultsTotal;
        Long currentGid;
        Instant startedAt, finishedAt;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final List<Map<String, Object>> results = new ArrayList<>();
    }
}
