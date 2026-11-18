package com.checker.web.archive;

import com.checker.model.dataobject.archive.ArchiveSyncReviewEntity;
import com.checker.service.archive.ArchiveSyncService;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.checker.shared.Constants;
import com.checker.shared.Result;
import com.checker.config.workflow.EhWorkflowConfig;
import com.checker.model.dto.workflow.WorkflowSettings;
import com.checker.entity.EhGalleriesEntity;
import com.checker.service.catalog.EhGalleriesService;
import com.checker.service.workflow.SingleGalleryDownloadWorkflow;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 提供群晖归档扫描、封面辅助匹配、人工确认同步及重新下载的接口。
 */
@RestController
@RequestMapping("/api/archive-sync")
@Tag(name = "归档同步", description = "归档扫描、封面匹配、审核与重新下载")
@PreAuthorize("hasRole('ADMIN')")
public class ArchiveSyncController {
    private final ArchiveSyncService syncService;
    private final EhGalleriesService galleriesService;
    private final WorkflowClient workflowClient;
    private final EhWorkflowConfig workflowConfig;

    public ArchiveSyncController(ArchiveSyncService syncService,
                                 EhGalleriesService galleriesService,
                                 WorkflowClient workflowClient,
                                 EhWorkflowConfig workflowConfig) {
        this.syncService = syncService;
        this.galleriesService = galleriesService;
        this.workflowClient = workflowClient;
        this.workflowConfig = workflowConfig;
    }

    /**
     * 返回归档扫描和封面匹配任务的当前进度。
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.success(syncService.scanStatus());
    }

    /**
     * 启动后台扫描，核对群晖文件与数据库记录之间的对应关系。
     */
    @PostMapping("/scan")
    public Result<Map<String, Object>> scan() {
        try {
            return Result.success(syncService.startScan());
        } catch (IllegalStateException failure) {
            return Result.error(409, failure.getMessage());
        }
    }

    /**
     * 为指定或待处理的归档记录启动封面对比，补充人工核对证据。
     */
    @PostMapping("/cover-match")
    public Result<Map<String, Object>> coverMatch(@RequestBody(required = false) CoverMatchRequest request) {
        try {
            return Result.success(syncService.startCoverMatch(request == null ? null : request.gids()));
        } catch (IllegalStateException failure) {
            return Result.error(409, failure.getMessage());
        }
    }

    /**
     * 按条件分页返回需要人工确认的归档匹配记录。
     */
    @GetMapping("/reviews")
    public Result<Map<String, Object>> reviews(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @RequestParam(defaultValue = "ACTIVE") String status) {
        return Result.success(syncService.reviews(page, size, status));
    }

    /**
     * 按用户选定的文件执行元数据与归档同步，不靠候选顺序随意选文件。
     */
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

    /**
     * 认领可重下的画廊并启动单本下载工作流，启动失败时保留可继续处理的状态。
     */
    @PostMapping("/{gid}/redownload")
    public Result<Map<String, String>> redownload(@PathVariable Long gid) {
        boolean claimed = false;
        try {
            syncService.claimRedownload(gid);
            claimed = true;
            EhGalleriesEntity gallery = galleriesService.getById(gid);
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

    /**
     * 复制当前工作流设置，作为本次重新下载的运行参数。
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

    public record FilenameRequest(String filename) {
    }

    public record CoverMatchRequest(List<Long> gids) {
    }
}
