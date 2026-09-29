package com.checker.controllers;

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

@RestController
@RequestMapping("/api/visual-dedup")
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

    @PostMapping("/refresh")
    public Result<VisualRefreshJobEntity> refresh(@RequestBody(required = false) Map<String, Boolean> request) {
        try {
            boolean force = request != null && Boolean.TRUE.equals(request.get("force"));
            return Result.success(refreshService.start(force));
        } catch (IllegalStateException exception) {
            return Result.error(409, exception.getMessage());
        }
    }

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

    @PostMapping("/ai-review")
    public Result<Map<String, Object>> startAiReview(@RequestBody AiReviewRequest request) {
        AiVisualReviewJobEntity job = null;
        try {
            if (request == null) return Result.error(400, "请求不能为空");
            job = reviewJobService.create(request.leftGid(), request.rightGid());
            String workflowId = "visual-review-" + job.getId();
            VisualReviewWorkflow workflow = workflowClient.newWorkflowStub(VisualReviewWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue(Constants.TASK_QUEUE)
                            .setWorkflowId(workflowId).build());
            WorkflowExecution execution = WorkflowClient.start(workflow::execute, job.getId());
            reviewJobService.attachWorkflow(job.getId(), execution.getWorkflowId());
            return Result.success(Map.of("jobId", job.getId(), "workflowId", execution.getWorkflowId()));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            if (job != null && job.getWorkflowId() == null) reviewJobService.markLaunchFailed(job.getId(), exception);
            return Result.error(400, exception.getMessage());
        } catch (RuntimeException exception) {
            if (job != null) reviewJobService.markLaunchFailed(job.getId(), exception);
            return Result.error(503, "Temporal 工作流启动失败: " + exception.getMessage());
        }
    }

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
