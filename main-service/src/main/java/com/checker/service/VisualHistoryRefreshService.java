package com.checker.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.checker.common.PerceptualHash;
import com.checker.entity.EhGalleriesEntity;
import com.checker.entity.VisualRefreshFailureEntity;
import com.checker.entity.VisualRefreshJobEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.mapper.VisualRefreshFailureMapper;
import com.checker.mapper.VisualRefreshJobMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class VisualHistoryRefreshService {
    private final EhGalleriesMapper galleriesMapper;
    private final VisualRefreshJobMapper jobMapper;
    private final VisualRefreshFailureMapper failureMapper;
    private final VisualFingerprintService fingerprintService;
    private final ArchiveVisualFingerprintExtractor extractor;
    private final SynologyArchiveReader archiveReader;
    private final DedupeReviewService reviewService;
    private final TaskExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public VisualHistoryRefreshService(EhGalleriesMapper galleriesMapper,
                                       VisualRefreshJobMapper jobMapper,
                                       VisualRefreshFailureMapper failureMapper,
                                       VisualFingerprintService fingerprintService,
                                       ArchiveVisualFingerprintExtractor extractor,
                                       SynologyArchiveReader archiveReader,
                                       DedupeReviewService reviewService,
                                       @Qualifier("backgroundTaskExecutor") TaskExecutor executor) {
        this.galleriesMapper = galleriesMapper;
        this.jobMapper = jobMapper;
        this.failureMapper = failureMapper;
        this.fingerprintService = fingerprintService;
        this.extractor = extractor;
        this.archiveReader = archiveReader;
        this.reviewService = reviewService;
        this.executor = executor;
    }

    public VisualRefreshJobEntity start(boolean force) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("已有视觉指纹刷新任务正在运行");
        }
        VisualRefreshJobEntity job = newJob(force);
        jobMapper.insert(job);
        executor.execute(() -> run(job.getId(), force, null));
        return jobMapper.selectById(job.getId());
    }

    public VisualRefreshJobEntity retry(List<Long> gids) {
        if (gids == null || gids.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个失败画廊");
        }
        List<Long> selectedGids = gids.stream()
                .filter(gid -> gid != null && gid > 0)
                .distinct()
                .toList();
        if (selectedGids.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个有效 GID");
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("已有视觉指纹刷新任务正在运行");
        }
        VisualRefreshJobEntity job = newJob(false);
        jobMapper.insert(job);
        executor.execute(() -> run(job.getId(), false, Set.copyOf(selectedGids)));
        return jobMapper.selectById(job.getId());
    }

    public VisualRefreshJobEntity latest() {
        QueryWrapper<VisualRefreshJobEntity> query = new QueryWrapper<>();
        query.orderByDesc("created_at").last("LIMIT 1");
        VisualRefreshJobEntity latest = jobMapper.selectOne(query);
        if (latest != null && Set.of("QUEUED", "RUNNING").contains(latest.getStatus()) && !running.get()) {
            latest.setStatus("FAILED");
            latest.setCurrentGid(null);
            latest.setFinishedAt(new Date());
            latest.setLastError("服务已重启或任务已中断，请重新执行失败项");
            jobMapper.updateById(latest);
        }
        return latest;
    }

    public long fingerprintedGalleries() {
        return fingerprintService.countCurrentGalleries();
    }

    public List<VisualRefreshFailureEntity> failures(String jobId) {
        if (jobId == null) return List.of();
        QueryWrapper<VisualRefreshFailureEntity> query = new QueryWrapper<>();
        query.eq("job_id", jobId).orderByAsc("gid");
        return failureMapper.selectList(query);
    }

    private void run(String jobId, boolean force, Set<Long> selectedGids) {
        VisualRefreshJobEntity job = jobMapper.selectById(jobId);
        try {
            QueryWrapper<EhGalleriesEntity> query = new QueryWrapper<>();
            query.isNotNull("filename").ne("filename", "").orderByAsc("gid");
            List<EhGalleriesEntity> all = galleriesMapper.selectList(query);
            List<EhGalleriesEntity> targets;
            if (selectedGids != null) {
                targets = all.stream().filter(gallery -> selectedGids.contains(gallery.getGid())).toList();
            } else {
                targets = force ? all : all.stream()
                        .filter(gallery -> !fingerprintService.hasArchiveFingerprints(gallery.getGid()))
                        .toList();
            }
            job.setStatus("RUNNING");
            job.setStartedAt(new Date());
            job.setTotal(targets.size());
            jobMapper.updateById(job);
            log.info("视觉指纹刷新开始, jobId: {}, force: {}, total: {}", jobId, force, targets.size());

            try (SynologyArchiveReader.ArchiveSession archiveSession = archiveReader.openSession()) {
                for (EhGalleriesEntity gallery : targets) {
                    job.setCurrentGid(gallery.getGid());
                    try {
                        int inserted = refreshGallery(archiveSession, gallery);
                        if (inserted <= 0) throw new IllegalStateException("归档中没有可解码的采样图片");
                        reviewService.refreshVisualEvidenceForGid(gallery.getGid());
                        job.setSucceeded(job.getSucceeded() + 1);
                    } catch (Exception failure) {
                        String error = truncate(describe(failure));
                        job.setFailed(job.getFailed() + 1);
                        job.setLastError("GID " + gallery.getGid() + ": " + error);
                        log.warn("视觉指纹刷新失败, jobId: {}, GID: {}, storagePath: {}, filename: {}, error: {}",
                                jobId, gallery.getGid(), gallery.getStoragePath(), gallery.getFilename(), error, failure);
                        VisualRefreshFailureEntity persistedFailure = new VisualRefreshFailureEntity();
                        persistedFailure.setJobId(jobId);
                        persistedFailure.setGid(gallery.getGid());
                        persistedFailure.setError(error);
                        failureMapper.insert(persistedFailure);
                    }
                    job.setProcessed(job.getProcessed() + 1);
                    jobMapper.updateById(job);
                }
            }
            job.setStatus(job.getFailed() > 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED");
            log.info("视觉指纹刷新完成, jobId: {}, status: {}, total: {}, succeeded: {}, failed: {}",
                    jobId, job.getStatus(), job.getTotal(), job.getSucceeded(), job.getFailed());
        } catch (Exception fatal) {
            job.setStatus("FAILED");
            job.setLastError(truncate(describe(fatal)));
            log.error("视觉指纹刷新任务异常终止, jobId: {}", jobId, fatal);
        } finally {
            job.setCurrentGid(null);
            job.setFinishedAt(new Date());
            jobMapper.updateById(job);
            running.set(false);
        }
    }

    private int refreshGallery(SynologyArchiveReader.ArchiveSession archiveSession,
                               EhGalleriesEntity gallery) throws Exception {
        String primaryDirectory = normalizeDirectory(gallery.getStoragePath());
        try {
            return readFingerprints(archiveSession, primaryDirectory, gallery.getFilename(), gallery);
        } catch (NoSuchFileException missingPrimary) {
            LinkedHashSet<String> fallbackDirectories = new LinkedHashSet<>();
            fallbackDirectories.add(normalizeDirectory(gallery.getSeriesCleanupPath()));
            fallbackDirectories.add("");
            fallbackDirectories.remove(primaryDirectory);

            for (String directory : fallbackDirectories) {
                Optional<String> resolved = SynologyArchiveReader.selectSeriesArchive(
                        gallery.getFilename(), gallery.getGid(), archiveSession.listArchives(directory));
                if (resolved.isEmpty()) continue;
                log.warn("视觉指纹归档路径已失效，使用回退路径, GID: {}, recordedPath: {}, actualPath: {}, filename: {}",
                        gallery.getGid(), primaryDirectory, directory, resolved.get());
                return readFingerprints(archiveSession, directory, resolved.get(), gallery);
            }
            throw new IOException("归档位置已失效且回退查找失败（记录目录: "
                    + displayDirectory(primaryDirectory) + "，文件: " + gallery.getFilename()
                    + "，已检查旧文件目录和归档根目录）", missingPrimary);
        }
    }

    private int readFingerprints(SynologyArchiveReader.ArchiveSession archiveSession,
                                 String directory, String filename,
                                 EhGalleriesEntity gallery) throws Exception {
        return archiveSession.read(directory, filename, input -> fingerprintService.replace(gallery.getGid(),
                extractor.extract(input, gallery.getGid(), gallery.getPageCount())));
    }

    private static String normalizeDirectory(String value) {
        return value == null ? "" : value.trim().replace('\\', '/');
    }

    private static String displayDirectory(String value) {
        return value == null || value.isBlank() ? "<归档根目录>" : value;
    }

    private static String describe(Throwable failure) {
        if (failure == null) return "unknown";
        String message = failure.getMessage();
        if (message != null && !message.isBlank()) return message;
        Throwable cause = failure.getCause();
        if (cause != null && cause != failure) return describe(cause);
        return failure.getClass().getSimpleName();
    }

    private VisualRefreshJobEntity newJob(boolean force) {
        VisualRefreshJobEntity job = new VisualRefreshJobEntity();
        job.setId(UUID.randomUUID().toString());
        job.setStatus("QUEUED");
        job.setForceRefresh(force);
        job.setAlgorithmVersion(PerceptualHash.ALGORITHM_VERSION);
        job.setTotal(0);
        job.setProcessed(0);
        job.setSucceeded(0);
        job.setFailed(0);
        return job;
    }

    private String truncate(String value) {
        if (value == null) return "unknown";
        return value.length() <= 950 ? value : value.substring(0, 950);
    }
}
