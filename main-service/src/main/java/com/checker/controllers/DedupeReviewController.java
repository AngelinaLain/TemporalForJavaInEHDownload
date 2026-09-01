package com.checker.controllers;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.checker.common.Constants;
import com.checker.common.DownloadStatus;
import com.checker.common.Result;
import com.checker.config.EhWorkflowConfig;
import com.checker.dto.DedupeReviewCase;
import com.checker.dto.ResolveDedupeReviewRequest;
import com.checker.dto.WorkflowSettings;
import com.checker.entity.DedupeReviewEntity;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.service.DedupeReviewService;
import com.checker.temporalServices.activities.impl.DatabaseActivityImpl;
import com.checker.temporalServices.workflows.SingleGalleryDownloadWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/dedupe-reviews")
@PreAuthorize("hasRole('ADMIN')")
public class DedupeReviewController {
    private final DedupeReviewService reviewService;
    private final EhGalleriesMapper galleriesMapper;
    private final DatabaseActivityImpl databaseActivity;
    private final WorkflowClient workflowClient;
    private final EhWorkflowConfig workflowConfig;

    public DedupeReviewController(DedupeReviewService reviewService,
                                  EhGalleriesMapper galleriesMapper,
                                  DatabaseActivityImpl databaseActivity,
                                  WorkflowClient workflowClient,
                                  EhWorkflowConfig workflowConfig) {
        this.reviewService = reviewService;
        this.galleriesMapper = galleriesMapper;
        this.databaseActivity = databaseActivity;
        this.workflowClient = workflowClient;
        this.workflowConfig = workflowConfig;
    }

    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "PENDING") String decision) {
        IPage<DedupeReviewEntity> result = reviewService.page(page, size, decision);
        List<Long> gids = result.getRecords().stream()
                .flatMap(review -> java.util.stream.Stream.of(review.getLeftGid(), review.getRightGid()))
                .distinct()
                .toList();
        Map<Long, EhGalleriesEntity> galleries = new HashMap<>();
        if (!gids.isEmpty()) {
            galleriesMapper.selectBatchIds(gids).forEach(gallery -> galleries.put(gallery.getGid(), gallery));
        }
        List<DedupeReviewCase> cases = result.getRecords().stream()
                .map(review -> toCase(review, galleries))
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("records", cases);
        payload.put("total", result.getTotal());
        payload.put("page", result.getCurrent());
        payload.put("size", result.getSize());
        payload.put("pendingCount", reviewService.countPending());
        return Result.success(payload);
    }

    @PostMapping("/{id}/resolve")
    public Result<Map<String, Object>> resolve(@PathVariable Long id,
                                               @RequestBody ResolveDedupeReviewRequest request,
                                               Authentication authentication) {
        try {
            String reviewer = authentication == null ? "admin" : authentication.getName();
            DedupeReviewService.ResolutionOutcome outcome = reviewService.resolve(
                    id, request.getDecision(), request.getPreferredGid(), reviewer);
            List<Map<String, String>> workflows = dispatch(outcome.dispatchGids());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reviewId", outcome.reviewId());
            payload.put("decision", outcome.decision());
            payload.put("dispatchGids", outcome.dispatchGids());
            payload.put("workflows", workflows);
            return Result.success(payload);
        } catch (IllegalArgumentException exception) {
            return Result.error(400, exception.getMessage());
        }
    }

    private List<Map<String, String>> dispatch(List<Long> gids) {
        List<Map<String, String>> started = new ArrayList<>();
        WorkflowSettings settings = buildSettings();
        for (Long gid : gids) {
            if (!databaseActivity.claimGalleryForDownload(gid)) continue;
            EhGalleriesEntity gallery = galleriesMapper.selectById(gid);
            if (gallery == null) continue;
            String workflowId = "dedupe-review-" + gid + "-" + UUID.randomUUID();
            try {
                SingleGalleryDownloadWorkflow workflow = workflowClient.newWorkflowStub(
                        SingleGalleryDownloadWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setTaskQueue(Constants.TASK_QUEUE)
                                .setWorkflowId(workflowId)
                                .build());
                WorkflowExecution execution = WorkflowClient.start(
                        workflow::processSingleGallery, gallery, false, settings);
                started.add(Map.of(
                        "gid", String.valueOf(gid),
                        "workflowId", execution.getWorkflowId(),
                        "runId", execution.getRunId()));
            } catch (RuntimeException exception) {
                databaseActivity.updateGalleryStatus(gid, DownloadStatus.PENDING.getValue());
                throw exception;
            }
        }
        return started;
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

    private DedupeReviewCase toCase(DedupeReviewEntity review,
                                    Map<Long, EhGalleriesEntity> galleries) {
        return DedupeReviewCase.builder()
                .id(review.getId())
                .candidateKey(review.getCandidateKey())
                .matchScore(review.getMatchScore())
                .matchReason(review.getMatchReason())
                .recommendedGid(review.getRecommendedGid())
                .decision(review.getDecision())
                .preferredGid(review.getPreferredGid())
                .reviewedBy(review.getReviewedBy())
                .reviewedAt(review.getReviewedAt())
                .createdAt(review.getCreatedAt())
                .left(galleries.get(review.getLeftGid()))
                .right(galleries.get(review.getRightGid()))
                .build();
    }
}
