package com.checker.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.checker.clients.KomgaApiClient;
import com.checker.common.ComicInfo;
import com.checker.common.ComicInfoInjector;
import com.checker.config.EhNetworkConfig;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 根据本地合集关系整理 Komga 的实际系列目录，并记录源文件清理进度以便失败后继续。 */
@Service
@Slf4j
public class KomgaSeriesSyncService {
    private final EhGalleriesMapper galleriesMapper;
    private final GallerySeriesPlacementService placementService;
    private final SynologyArchiveReader archiveReader;
    private final SynologyUploadService uploadService;
    private final KomgaApiClient komgaApiClient;
    private final EhNetworkConfig networkConfig;
    private final TaskExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger processed = new AtomicInteger();
    private final AtomicInteger succeeded = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger cleanupPending = new AtomicInteger();
    private volatile int total;
    private volatile Long currentGid;
    private volatile String lastError;
    private volatile Date startedAt;
    private volatile Date finishedAt;

    public KomgaSeriesSyncService(EhGalleriesMapper galleriesMapper,
                                  GallerySeriesPlacementService placementService,
                                  SynologyArchiveReader archiveReader,
                                  SynologyUploadService uploadService,
                                  KomgaApiClient komgaApiClient,
                                  EhNetworkConfig networkConfig,
                                  @Qualifier("backgroundTaskExecutor") TaskExecutor executor) {
        this.galleriesMapper = galleriesMapper;
        this.placementService = placementService;
        this.archiveReader = archiveReader;
        this.uploadService = uploadService;
        this.komgaApiClient = komgaApiClient;
        this.networkConfig = networkConfig;
        this.executor = executor;
    }

    /** 建立一次系列同步任务并交给后台执行，已有任务运行时拒绝重复启动。 */
    public synchronized Map<String, Object> start() {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("Komga 系列同步正在运行");
        processed.set(0);
        succeeded.set(0);
        failed.set(0);
        cleanupPending.set(0);
        total = 0;
        currentGid = null;
        lastError = null;
        startedAt = new Date();
        finishedAt = null;
        try {
            executor.execute(this::run);
        } catch (RuntimeException failure) {
            running.set(false);
            throw failure;
        }
        return status();
    }

    /** 汇总同步进度、错误和待清理副本数量。 */
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("running", running.get());
        result.put("total", total);
        result.put("processed", processed.get());
        result.put("succeeded", succeeded.get());
        result.put("failed", failed.get());
        result.put("cleanupPending", cleanupPending.get());
        result.put("currentGid", currentGid);
        result.put("lastError", lastError);
        result.put("startedAt", startedAt);
        result.put("finishedAt", finishedAt);
        return result;
    }

    /** 只挑出目录、签名或清理日志确有变化的记录，逐本同步并汇总结果。 */
    private void run() {
        try {
            QueryWrapper<EhGalleriesEntity> query = new QueryWrapper<>();
            query.isNotNull("filename").ne("filename", "").isNull("duplicate_of_gid").orderByAsc("gid");
            List<SyncTarget> targets = new ArrayList<>();
            for (EhGalleriesEntity gallery : galleriesMapper.selectList(query)) {
                GallerySeriesPlacementService.SeriesPlacement placement = placementService.resolve(gallery.getGid());
                String currentPath = normalizePath(gallery.getStoragePath());
                if (gallery.getSeriesCleanupFilename() != null
                        || !Objects.equals(currentPath, placement.relativeDirectory())
                        || !Objects.equals(gallery.getSeriesSyncSignature(), placement.signature())) {
                    // 未归组且目录未变的旧书不必仅为补签名而重写，避免无意义地处理整个图库。
                    if (gallery.getSeriesCleanupFilename() != null
                            || placement.collectionId() != null || !currentPath.isBlank()) {
                        targets.add(new SyncTarget(gallery, placement));
                    }
                }
            }
            total = targets.size();
            for (SyncTarget target : targets) {
                currentGid = target.gallery().getGid();
                try {
                    synchronize(target);
                    succeeded.incrementAndGet();
                } catch (Exception failure) {
                    failed.incrementAndGet();
                    lastError = "GID " + currentGid + ": " + truncate(rootMessage(failure));
                    log.warn("Komga 系列同步失败，GID {}: {}", currentGid, lastError, failure);
                } finally {
                    processed.incrementAndGet();
                }
            }
            if (succeeded.get() > 0) komgaApiClient.triggerLibraryScan();
        } catch (Exception failure) {
            lastError = truncate(rootMessage(failure));
        } finally {
            currentGid = null;
            finishedAt = new Date();
            running.set(false);
        }
    }

    /** 读取旧归档、写入目标目录并更新数据库位置，源副本的删除放在确认目标之后。 */
    private void synchronize(SyncTarget target) throws Exception {
        EhGalleriesEntity gallery = target.gallery();
        GallerySeriesPlacementService.SeriesPlacement placement = target.placement();
        if (!cleanupPreviousArchive(gallery)) throw new IOException("旧文件清理仍未完成，保留待重试记录");
        String sourceDirectory = normalizePath(gallery.getStoragePath());
        if (sourceDirectory.equals(placement.relativeDirectory())
                && Objects.equals(gallery.getSeriesSyncSignature(), placement.signature())) return;

        Optional<String> source = archiveReader.resolveArchive(sourceDirectory, gallery.getFilename(), gallery.getGid());
        String readDirectory = sourceDirectory;
        if (!sourceDirectory.equals(placement.relativeDirectory())) {
            Optional<String> destination = archiveReader.resolveArchive(
                    placement.relativeDirectory(), gallery.getFilename(), gallery.getGid());
            if (source.isEmpty()) {
                readDirectory = placement.relativeDirectory();
                source = destination;
            }
        }
        String sourceFilename = source.orElseThrow(() -> new IOException("GID " + gallery.getGid()
                + " 在原目录及目标系列目录均未找到归档，请先进行群晖归档同步核查"));
        String targetFilename = SynologyArchiveReader.isArchiveForSameGid(
                "[" + gallery.getGid() + "]", gallery.getFilename())
                ? gallery.getFilename() : sourceFilename;
        Path local = createTempArchive(gallery.getGid());
        try {
            archiveReader.read(readDirectory, sourceFilename, input -> {
                Files.copy(input, local, StandardCopyOption.REPLACE_EXISTING);
                return null;
            });
            validateArchive(local);
            ComicInfoInjector.inject(local, buildComicInfo(gallery, placement));
            uploadService.upload(local, placement.relativeDirectory(), targetFilename, bytes -> { });

            boolean needsCleanup = !readDirectory.equals(placement.relativeDirectory())
                    || !sourceFilename.equals(targetFilename);
        // 先把新位置和待清理源文件写入数据库，再删除旧副本；写库失败保留源文件，清理失败留待重试。
            UpdateWrapper<EhGalleriesEntity> update = currentRecord(gallery)
                    .set("filename", targetFilename)
                    .set("storage_path", placement.relativeDirectory())
                    .set("series_sync_signature", placement.signature())
                    .set("series_cleanup_path", needsCleanup ? readDirectory : null)
                    .set("series_cleanup_filename", needsCleanup ? sourceFilename : null);
            if (galleriesMapper.update(null, update) != 1) {
                throw new IOException("画廊记录已变化，保留源文件，请重新同步 GID " + gallery.getGid());
            }
            gallery.setFilename(targetFilename);
            gallery.setStoragePath(placement.relativeDirectory());
            gallery.setSeriesSyncSignature(placement.signature());
            gallery.setSeriesCleanupPath(needsCleanup ? readDirectory : null);
            gallery.setSeriesCleanupFilename(needsCleanup ? sourceFilename : null);
            cleanupPreviousArchive(gallery);
        } finally {
            Files.deleteIfExists(local);
            Files.deleteIfExists(local.resolveSibling(local.getFileName() + ".inject.tmp"));
        }
    }

    /** 先确认目标副本仍存在，再按日志精确清理旧副本，失败则保留待重试状态。 */
    private boolean cleanupPreviousArchive(EhGalleriesEntity gallery) {
        if (gallery.getSeriesCleanupFilename() == null) return true;
        try {
            String oldDirectory = normalizePath(gallery.getSeriesCleanupPath());
            if (oldDirectory.equals(normalizePath(gallery.getStoragePath()))
                    && gallery.getSeriesCleanupFilename().equals(gallery.getFilename())) {
                throw new IOException("清理路径与当前归档相同，已阻止删除");
            }
            // 数据库虽已记录新位置，但目标文件若已消失，仍要保留旧副本，避免两边都没有文件。
            if (!archiveReader.existsExact(normalizePath(gallery.getStoragePath()), gallery.getFilename())) {
                throw new IOException("目标归档不存在，保留旧文件");
            }
            archiveReader.deleteExact(oldDirectory, gallery.getSeriesCleanupFilename());
            UpdateWrapper<EhGalleriesEntity> update = currentRecord(gallery)
                    .eq("series_cleanup_filename", gallery.getSeriesCleanupFilename())
                    .set("series_cleanup_path", null).set("series_cleanup_filename", null);
            if (galleriesMapper.update(null, update) != 1) throw new IOException("清理记录已变化，请重试");
            gallery.setSeriesCleanupFilename(null);
            gallery.setSeriesCleanupPath(null);
            return true;
        } catch (Exception failure) {
            cleanupPending.incrementAndGet();
            lastError = "GID " + gallery.getGid() + " 已记录目标路径，旧文件清理待重试: " + truncate(rootMessage(failure));
            log.warn(lastError, failure);
            return false;
        }
    }

    private UpdateWrapper<EhGalleriesEntity> currentRecord(EhGalleriesEntity gallery) {
        UpdateWrapper<EhGalleriesEntity> update = new UpdateWrapper<EhGalleriesEntity>()
                .eq("gid", gallery.getGid()).eq("filename", gallery.getFilename());
        if (gallery.getStoragePath() == null) update.isNull("storage_path");
        else update.eq("storage_path", gallery.getStoragePath());
        if (gallery.getSeriesSyncSignature() == null) update.isNull("series_sync_signature");
        else update.eq("series_sync_signature", gallery.getSeriesSyncSignature());
        return update;
    }

    /** 检查归档是否可读且包含需要的内容，避免发布不完整的目标文件。 */
    private static void validateArchive(Path archive) throws IOException {
        try (org.apache.commons.compress.archivers.zip.ZipFile zip =
                     new org.apache.commons.compress.archivers.zip.ZipFile(archive.toFile())) {
            boolean image = false;
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName().toLowerCase(Locale.ROOT);
                image |= name.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|avif|jxl)");
                try (var input = zip.getInputStream(entry)) {
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    byte[] buffer = new byte[64 * 1024];
                    long size = 0;
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        crc.update(buffer, 0, n);
                        size += n;
                    }
                    if (size != entry.getSize() || crc.getValue() != entry.getCrc()) {
                        throw new IOException("归档条目校验失败: " + entry.getName());
                    }
                }
            }
            if (!image) throw new IOException("归档内没有图片，拒绝同步");
        }
    }

    private ComicInfo buildComicInfo(EhGalleriesEntity gallery,
                                     GallerySeriesPlacementService.SeriesPlacement placement) {
        List<String> writers = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        if (gallery.getTags() != null) {
            for (String tag : gallery.getTags()) {
                if (tag != null && (tag.startsWith("artist:") || tag.startsWith("group:"))) {
                    writers.add(tag.substring(tag.indexOf(':') + 1));
                } else if (tag != null) {
                    tags.add(tag);
                }
            }
        }
        return ComicInfo.builder().title(gallery.getTitle()).series(placement.seriesName())
                .number(placement.number()).summary(gallery.getSummary()).writers(writers).tags(tags).build();
    }

    private Path createTempArchive(Long gid) throws IOException {
        String configured = networkConfig.getDownload() == null ? null : networkConfig.getDownload().getTempDir();
        Path directory = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir")) : Path.of(configured);
        Files.createDirectories(directory);
        return Files.createTempFile(directory, "series-sync-" + gid + "-", ".cbz");
    }

    private static String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/').replaceAll("^/+|/+$", "");
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String outer = failure.getMessage();
        String root = current.getMessage();
        String summary = failure.getClass().getSimpleName()
                + (outer == null || outer.isBlank() ? "" : ": " + outer);
        if (current != failure && root != null && !root.isBlank() && !String.valueOf(outer).contains(root)) {
            summary += "；根因: " + current.getClass().getSimpleName() + ": " + root;
        }
        return summary;
    }

    private static String truncate(String value) {
        return value == null ? "unknown" : value.substring(0, Math.min(950, value.length()));
    }

    private record SyncTarget(EhGalleriesEntity gallery,
                              GallerySeriesPlacementService.SeriesPlacement placement) {
    }
}
