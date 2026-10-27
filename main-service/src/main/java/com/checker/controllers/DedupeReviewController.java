package com.checker.controllers;

import io.swagger.v3.oas.annotations.tags.Tag;
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
import com.checker.service.AiVisualReviewJobService;
import com.checker.entity.AiVisualReviewJobEntity;
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

/**
 * 提供疑似重复画廊的人工审核接口，并在用户确认后派发需要继续下载的版本。
 */
@RestController
@RequestMapping("/api/dedupe-reviews")
@Tag(name = "去重审核", description = "重复图库候选项审核与 AI 辅助判定")
@PreAuthorize("hasRole('ADMIN')")
public class DedupeReviewController {
    private final DedupeReviewService reviewService;
    private final EhGalleriesMapper galleriesMapper;
    private final DatabaseActivityImpl databaseActivity;
    private final WorkflowClient workflowClient;
    private final EhWorkflowConfig workflowConfig;
    private final AiVisualReviewJobService visualReviewJobService;

    public DedupeReviewController(DedupeReviewService reviewService,
                                  EhGalleriesMapper galleriesMapper,
                                  DatabaseActivityImpl databaseActivity,
                                  WorkflowClient workflowClient,
                                  EhWorkflowConfig workflowConfig,
                                  AiVisualReviewJobService visualReviewJobService) {
        this.reviewService = reviewService;
        this.galleriesMapper = galleriesMapper;
        this.databaseActivity = databaseActivity;
        this.workflowClient = workflowClient;
        this.workflowConfig = workflowConfig;
        this.visualReviewJobService = visualReviewJobService;
    }

    /**
     * 分页返回审核记录，并批量补充左右两本画廊的信息，方便并排比较。
     */
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

    /**
     * 为当前审核记录的候选对启动 AI 视觉复核，只提供辅助证据，不自动提交人工结论。
     */
    @PostMapping("/{id}/ai-review")
    public Result<Map<String, Object>> startAiReview(@PathVariable Long id) {
        try {
            DedupeReviewEntity review = reviewService.require(id);
            AiVisualReviewJobEntity job = visualReviewJobService.launch(review.getLeftGid(), review.getRightGid());
            return Result.success(Map.of("jobId", job.getId(), "workflowId", job.getWorkflowId()));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return Result.error(400, exception.getMessage());
        } catch (RuntimeException exception) {
            return Result.error(503, "Temporal 工作流启动失败: " + exception.getMessage());
        }
    }
    /**
     * 保存人工结论，再为需要继续处理的画廊启动下载工作流。
     */
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

    /**
     * 逐个为获准继续处理的画廊建立子任务，并返回实际派发的工作流标识。
     */
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

    /**
     * 为本次补偿下载构建工作流参数快照。
     */
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

    /**
     * 把审核结论、视觉证据和两侧画廊组合成前端审核卡片所需的数据。
     */
    private DedupeReviewCase toCase(DedupeReviewEntity review,
                                    Map<Long, EhGalleriesEntity> galleries) {
        return DedupeReviewCase.builder()
                .id(review.getId())
                .candidateKey(review.getCandidateKey())
                .matchScore(review.getMatchScore())
                .matchReason(review.getMatchReason())
                .visualSimilarity(review.getVisualSimilarity())
                .visualMatchedPages(review.getVisualMatchedPages())
                .visualSampleCoverage(review.getVisualSampleCoverage())
                .visualOrderConsistency(review.getVisualOrderConsistency())
                .visualRecommendedGid(review.getVisualRecommendedGid())
                .visualQualityDelta(review.getVisualQualityDelta())
                .visualReason(review.getVisualReason())
                .visualAlgorithmVersion(review.getVisualAlgorithmVersion())
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
