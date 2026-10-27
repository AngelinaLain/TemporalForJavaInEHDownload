package com.checker.controllers;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.checker.common.PerceptualHash;
import com.checker.common.Result;
import com.checker.entity.VisualRefreshJobEntity;
import com.checker.entity.AiVisualReviewJobEntity;
import com.checker.common.Constants;
import com.checker.service.AiVisualReviewJobService;
import com.checker.service.VisualHistoryRefreshService;
import com.checker.temporalServices.workflows.VisualReviewWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowOptions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供视觉指纹刷新与 AI 视觉复核的任务入口，返回进度和辅助判断结果。
 */
@RestController
@RequestMapping("/api/visual-dedup")
@Tag(name = "视觉去重", description = "视觉指纹刷新与 AI 去重审核")
@PreAuthorize("hasRole('ADMIN')")
public class VisualDeduplicationController {
    private final VisualHistoryRefreshService refreshService;
    private final AiVisualReviewJobService reviewJobService;
    private final WorkflowClient workflowClient;

    public VisualDeduplicationController(VisualHistoryRefreshService refreshService,
                                         AiVisualReviewJobService reviewJobService,
                                         WorkflowClient workflowClient) {
        this.refreshService = refreshService;
        this.reviewJobService = reviewJobService;
        this.workflowClient = workflowClient;
    }

    /**
     * 返回当前指纹算法版本、已生成数量和最近任务状态。
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("algorithmVersion", PerceptualHash.ALGORITHM_VERSION);
        payload.put("fingerprintedGalleries", refreshService.fingerprintedGalleries());
        VisualRefreshJobEntity latestJob = refreshService.latest();
        payload.put("latestJob", latestJob);
        payload.put("failedGalleries", refreshService.failures(latestJob == null ? null : latestJob.getId()));
        return Result.success(payload);
    }

    /**
     * 启动历史画廊指纹刷新，可按请求决定是否强制重新计算。
     */
    @PostMapping("/refresh")
    public Result<VisualRefreshJobEntity> refresh(@RequestBody(required = false) Map<String, Boolean> request) {
        try {
            boolean force = request != null && Boolean.TRUE.equals(request.get("force"));
            return Result.success(refreshService.start(force));
        } catch (IllegalStateException exception) {
            return Result.error(409, exception.getMessage());
        }
    }

    /**
     * 对用户指定的画廊重新计算归档指纹，常用于重试失败项。
     */
    @PostMapping("/refresh/retry")
    public Result<VisualRefreshJobEntity> retry(@RequestBody RetryRequest request) {
        try {
            return Result.success(refreshService.retry(request == null ? null : request.gids()));
        } catch (IllegalArgumentException exception) {
            return Result.error(400, exception.getMessage());
        } catch (IllegalStateException exception) {
            return Result.error(409, exception.getMessage());
        }
    }

    /**
     * 为两本画廊启动视觉复核，参数不合法或服务不可用时返回可读错误。
     */
    @PostMapping("/ai-review")
    public Result<Map<String, Object>> startAiReview(@RequestBody AiReviewRequest request) {
        AiVisualReviewJobEntity job = null;
        try {
            if (request == null) return Result.error(400, "请求不能为空");
            job = reviewJobService.launch(request.leftGid(), request.rightGid());
            return Result.success(Map.of("jobId", job.getId(), "workflowId", job.getWorkflowId()));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            if (job != null && job.getWorkflowId() == null) reviewJobService.markLaunchFailed(job.getId(), exception);
            return Result.error(400, exception.getMessage());
        } catch (RuntimeException exception) {
            if (job != null) reviewJobService.markLaunchFailed(job.getId(), exception);
            return Result.error(503, "Temporal 工作流启动失败: " + exception.getMessage());
        }
    }

    /**
     * 读取指定 AI 视觉复核任务及其判断结果。
     */
    @GetMapping("/ai-review/{jobId}")
    public Result<Map<String, Object>> aiReview(@org.springframework.web.bind.annotation.PathVariable String jobId) {
        try {
            return Result.success(reviewJobService.view(jobId));
        } catch (IllegalArgumentException exception) {
            return Result.error(404, exception.getMessage());
        }
    }

    public record RetryRequest(List<Long> gids) {
    }

    public record AiReviewRequest(long leftGid, long rightGid) {
    }
}
