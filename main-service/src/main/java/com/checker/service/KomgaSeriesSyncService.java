package com.checker.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.checker.clients.KomgaApiClient;
import com.checker.common.ComicInfo;
import com.checker.common.ComicInfoInjector;
import com.checker.config.EhNetworkConfig;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Reconciles local collection membership to physical Komga series directories. */
@Service
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

    public synchronized Map<String, Object> start() {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("Komga 系列同步正在运行");
        processed.set(0);
        succeeded.set(0);
        failed.set(0);
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

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("running", running.get());
        result.put("total", total);
        result.put("processed", processed.get());
        result.put("succeeded", succeeded.get());
        result.put("failed", failed.get());
        result.put("currentGid", currentGid);
        result.put("lastError", lastError);
        result.put("startedAt", startedAt);
        result.put("finishedAt", finishedAt);
        return result;
    }

    private void run() {
        try {
            QueryWrapper<EhGalleriesEntity> query = new QueryWrapper<>();
            query.isNotNull("filename").ne("filename", "").isNull("duplicate_of_gid").orderByAsc("gid");
            List<SyncTarget> targets = new ArrayList<>();
            for (EhGalleriesEntity gallery : galleriesMapper.selectList(query)) {
                GallerySeriesPlacementService.SeriesPlacement placement = placementService.resolve(gallery.getGid());
                String currentPath = normalizePath(gallery.getStoragePath());
                if (!Objects.equals(currentPath, placement.relativeDirectory())
                        || !Objects.equals(gallery.getSeriesSyncSignature(), placement.signature())) {
                    // Never rewrite thousands of untouched unassigned books merely to add a signature.
                    if (placement.collectionId() != null || !currentPath.isBlank()) {
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

    private void synchronize(SyncTarget target) throws Exception {
        EhGalleriesEntity gallery = target.gallery();
        GallerySeriesPlacementService.SeriesPlacement placement = target.placement();
        String sourceDirectory = normalizePath(gallery.getStoragePath());
        Path local = createTempArchive(gallery.getGid());
        try {
            archiveReader.read(sourceDirectory, gallery.getFilename(), input -> {
                Files.copy(input, local, StandardCopyOption.REPLACE_EXISTING);
                return null;
            });
            ComicInfoInjector.inject(local, buildComicInfo(gallery, placement));
            uploadService.upload(local, placement.relativeDirectory(), gallery.getFilename(), bytes -> { });
            if (!sourceDirectory.equals(placement.relativeDirectory())) {
                archiveReader.delete(sourceDirectory, gallery.getFilename());
            }

            EhGalleriesEntity update = new EhGalleriesEntity();
            update.setGid(gallery.getGid());
            update.setStoragePath(placement.relativeDirectory());
            update.setSeriesSyncSignature(placement.signature());
            galleriesMapper.updateById(update);
        } finally {
            Files.deleteIfExists(local);
            Files.deleteIfExists(local.resolveSibling(local.getFileName() + ".inject.tmp"));
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
        return current.getClass().getSimpleName() + ": " + String.valueOf(current.getMessage());
    }

    private static String truncate(String value) {
        return value == null ? "unknown" : value.substring(0, Math.min(950, value.length()));
    }

    private record SyncTarget(EhGalleriesEntity gallery,
                              GallerySeriesPlacementService.SeriesPlacement placement) {
    }
}
