package com.checker.controllers;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.checker.common.Constants;
import com.checker.common.Result;
import com.checker.config.EhWorkflowConfig;
import com.checker.dto.WorkflowSettings;
import com.checker.entity.ArchiveSyncReviewEntity;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.ArchiveSyncReviewMapper;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.service.ArchiveSyncService;
import com.checker.temporalServices.workflows.SingleGalleryDownloadWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/archive-sync")
@PreAuthorize("hasRole('ADMIN')")
public class ArchiveSyncController {
    private final ArchiveSyncService syncService;
    private final ArchiveSyncReviewMapper reviewMapper;
    private final EhGalleriesMapper galleriesMapper;
    private final WorkflowClient workflowClient;
    private final EhWorkflowConfig workflowConfig;

    public ArchiveSyncController(ArchiveSyncService syncService,
                                 ArchiveSyncReviewMapper reviewMapper,
                                 EhGalleriesMapper galleriesMapper,
                                 WorkflowClient workflowClient,
                                 EhWorkflowConfig workflowConfig) {
        this.syncService = syncService;
        this.reviewMapper = reviewMapper;
        this.galleriesMapper = galleriesMapper;
        this.workflowClient = workflowClient;
        this.workflowConfig = workflowConfig;
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.success(syncService.scanStatus());
    }

    @PostMapping("/scan")
    public Result<Map<String, Object>> scan() {
        try {
            return Result.success(syncService.startScan());
        } catch (IllegalStateException failure) {
            return Result.error(409, failure.getMessage());
        }
    }

    @GetMapping("/reviews")
    public Result<Map<String, Object>> reviews(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @RequestParam(defaultValue = "ACTIVE") String status) {
        QueryWrapper<ArchiveSyncReviewEntity> query = new QueryWrapper<>();
        if ("ACTIVE".equalsIgnoreCase(status)) {
            query.in("status", List.of("PENDING", "SYNCING", "FAILED"));
        } else if (!"ALL".equalsIgnoreCase(status)) {
            query.eq("status", status.toUpperCase(Locale.ROOT));
        }
        query.orderByAsc("status").orderByAsc("match_type").orderByAsc("gid");
        IPage<ArchiveSyncReviewEntity> result = reviewMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size))), query);

        Map<String, Long> owners = syncService.databaseFilenameOwners();
        List<Long> gids = result.getRecords().stream().map(ArchiveSyncReviewEntity::getGid).toList();
        Map<Long, EhGalleriesEntity> galleries = new HashMap<>();
        if (!gids.isEmpty()) {
            for (EhGalleriesEntity gallery : galleriesMapper.selectBatchIds(gids)) galleries.put(gallery.getGid(), gallery);
        }
        List<Map<String, Object>> records = result.getRecords().stream()
                .map(review -> toView(review, galleries.get(review.getGid()), owners))
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("records", records);
        payload.put("total", result.getTotal());
        payload.put("page", result.getCurrent());
        payload.put("size", result.getSize());
        return Result.success(payload);
    }

    @PostMapping("/{gid}/synchronize")
    public Result<ArchiveSyncReviewEntity> synchronize(@PathVariable Long gid,
                                                        @RequestBody FilenameRequest request) {
        try {
            return Result.success(syncService.synchronize(gid, request == null ? null : request.filename()));
        } catch (IllegalArgumentException failure) {
            return Result.error(400, failure.getMessage());
        } catch (IllegalStateException failure) {
            return Result.error(409, failure.getMessage());
        }
    }

    @PostMapping("/{gid}/redownload")
    public Result<Map<String, String>> redownload(@PathVariable Long gid) {
        boolean claimed = false;
        try {
            syncService.claimRedownload(gid);
            claimed = true;
            EhGalleriesEntity gallery = galleriesMapper.selectById(gid);
            if (gallery == null) {
                syncService.markRedownloadFailed(gid, new IllegalStateException("画廊记录不存在"));
                return Result.error(404, "画廊记录不存在");
            }
            String workflowId = "archive-sync-redownload-" + gid + "-" + UUID.randomUUID();
            SingleGalleryDownloadWorkflow workflow = workflowClient.newWorkflowStub(
                    SingleGalleryDownloadWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue(Constants.TASK_QUEUE)
                            .setWorkflowId(workflowId).build());
            WorkflowExecution execution = WorkflowClient.start(
                    workflow::processSingleGallery, gallery, false, buildSettings());
            syncService.markRedownloadStarted(gid, execution.getWorkflowId());
            return Result.success(Map.of("gid", String.valueOf(gid),
                    "workflowId", execution.getWorkflowId(), "runId", execution.getRunId()));
        } catch (IllegalArgumentException failure) {
            return Result.error(404, failure.getMessage());
        } catch (RuntimeException failure) {
            if (claimed) syncService.markRedownloadFailed(gid, failure);
            return Result.error("启动重新下载失败: " + failure.getMessage());
        }
    }

    private Map<String, Object> toView(ArchiveSyncReviewEntity review, EhGalleriesEntity gallery,
                                       Map<String, Long> owners) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("gid", review.getGid());
        view.put("title", review.getTitle());
        view.put("expectedFilename", review.getExpectedFilename());
        view.put("selectedFilename", review.getSelectedFilename());
        view.put("matchType", review.getMatchType());
        view.put("status", review.getStatus());
        view.put("message", review.getMessage());
        view.put("galleryUrl", gallery == null ? null : gallery.getGalleryUrl());
        List<Map<String, Object>> candidates = new ArrayList<>();
        if (review.getCandidateFilenames() != null) {
            for (String filename : review.getCandidateFilenames()) {
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("filename", filename);
                Long owner = owners.get(filename.toLowerCase(Locale.ROOT));
                candidate.put("databaseGid", owner != null && !owner.equals(review.getGid()) ? owner : null);
                candidates.add(candidate);
            }
        }
        view.put("candidates", candidates);
        return view;
    }

    private WorkflowSettings buildSettings() {
        return WorkflowSettings.builder()
                .maxConcurrency(workflowConfig.getMaxConcurrency())
                .komgaImportMaxRetries(workflowConfig.getKomgaImportMaxRetries())
                .komgaImportPollIntervalSeconds(workflowConfig.getKomgaImportPollIntervalSeconds())
                .downloadPollIntervalMinutes(workflowConfig.getDownloadPollIntervalMinutes())
                .downloadCooldownSeconds(workflowConfig.getDownloadCooldownSeconds())
                .downloadMode(workflowConfig.getDownloadMode())
                .build();
    }

    public record FilenameRequest(String filename) {
    }
}
