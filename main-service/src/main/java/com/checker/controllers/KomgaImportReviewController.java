package com.checker.controllers;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.checker.common.Constants;
import com.checker.common.DownloadStatus;
import com.checker.common.Result;
import com.checker.config.EhWorkflowConfig;
import com.checker.dto.KomgaImportReviewItem;
import com.checker.dto.WorkflowSettings;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.temporalServices.workflows.SingleGalleryDownloadWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Komga 入库失败复核与补偿入口。重试只启动 Komga 补偿流程，不会重新下载文件。
 */
@RestController
@RequestMapping("/api/komga-import-reviews")
@PreAuthorize("hasRole('ADMIN')")
public class KomgaImportReviewController {
    private final EhGalleriesMapper galleriesMapper;
    private final WorkflowClient workflowClient;
    private final EhWorkflowConfig workflowConfig;

    public KomgaImportReviewController(EhGalleriesMapper galleriesMapper,
                                       WorkflowClient workflowClient,
                                       EhWorkflowConfig workflowConfig) {
        this.galleriesMapper = galleriesMapper;
        this.workflowClient = workflowClient;
        this.workflowConfig = workflowConfig;
    }

    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "KOMGA_IMPORT_FAILED") String status) {
        QueryWrapper<EhGalleriesEntity> query = new QueryWrapper<>();
        if (!"ALL".equalsIgnoreCase(status)) {
            String normalized = normalizeStatus(status);
            if (normalized == null) return Result.error(400, "不支持的 Komga 复核状态");
            query.and(wrapper -> wrapper.eq("download_status", normalized)
                    .or().eq("download_status", statusLabel(normalized)));
        }
        query.orderByDesc("komga_last_confirmation_at")
                .orderByDesc("updated_at")
                .orderByDesc("gid");
        IPage<EhGalleriesEntity> result = galleriesMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(Math.max(1, size), 100)), query);

        List<KomgaImportReviewItem> records = result.getRecords().stream()
                .map(this::toItem)
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("records", records);
        payload.put("total", result.getTotal());
        payload.put("page", result.getCurrent());
        payload.put("size", result.getSize());
        payload.put("failedCount", countByStatus(DownloadStatus.KOMGA_IMPORT_FAILED));
        payload.put("waitingCount", countByStatus(DownloadStatus.WAITING_KOMGA));
        return Result.success(payload);
    }

    @PostMapping("/{gid}/retry")
    public Result<Map<String, String>> retry(@PathVariable Long gid) {
        EhGalleriesEntity gallery = galleriesMapper.selectById(gid);
        if (gallery == null) return Result.error(404, "画廊记录不存在");
        if (!hasStatus(gallery, DownloadStatus.KOMGA_IMPORT_FAILED)
                && !hasStatus(gallery, DownloadStatus.DOWNLOADED)) {
            return Result.error(409, "当前状态不允许 Komga 补偿: " + gallery.getDownloadStatus());
        }

        UpdateWrapper<EhGalleriesEntity> claim = new UpdateWrapper<>();
        claim.eq("gid", gid)
                .and(wrapper -> wrapper.eq("download_status", DownloadStatus.KOMGA_IMPORT_FAILED.getValue())
                        .or().eq("download_status", DownloadStatus.KOMGA_IMPORT_FAILED.name())
                        .or().eq("download_status", DownloadStatus.DOWNLOADED.getValue())
                        .or().eq("download_status", DownloadStatus.DOWNLOADED.name()))
                .set("download_status", DownloadStatus.WAITING_KOMGA.getValue());
        if (galleriesMapper.update(null, claim) != 1) {
            return Result.error(409, "该画廊已被其他补偿任务处理");
        }

        String workflowId = "komga-review-" + gid + "-" + UUID.randomUUID();
        try {
            WorkflowSettings settings = buildSettings();
            SingleGalleryDownloadWorkflow workflow = workflowClient.newWorkflowStub(
                    SingleGalleryDownloadWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setTaskQueue(Constants.TASK_QUEUE)
                            .setWorkflowId(workflowId)
                            .build());
            WorkflowExecution execution = WorkflowClient.start(
                    workflow::processSingleGallery, gallery, true, settings);
            return Result.success(Map.of(
                    "gid", String.valueOf(gid),
                    "workflowId", execution.getWorkflowId(),
                    "runId", execution.getRunId()));
        } catch (RuntimeException exception) {
            UpdateWrapper<EhGalleriesEntity> rollback = new UpdateWrapper<>();
            rollback.eq("gid", gid).set("download_status", DownloadStatus.KOMGA_IMPORT_FAILED.getValue());
            galleriesMapper.update(null, rollback);
            return Result.error("启动 Komga 补偿流程失败: " + exception.getMessage());
        }
    }

    private long countByStatus(DownloadStatus status) {
        QueryWrapper<EhGalleriesEntity> query = new QueryWrapper<>();
        query.and(wrapper -> wrapper.eq("download_status", status.getValue())
                .or().eq("download_status", status.name()));
        return galleriesMapper.selectCount(query);
    }

    private KomgaImportReviewItem toItem(EhGalleriesEntity gallery) {
        return KomgaImportReviewItem.builder()
                .gid(gallery.getGid())
                .title(gallery.getTitle())
                .filename(gallery.getFilename())
                .galleryUrl(gallery.getGalleryUrl())
                .downloadStatus(normalizeStatus(gallery.getDownloadStatus()))
                .komgaBookId(gallery.getKomgaBookId())
                .confirmationAttempts(gallery.getKomgaConfirmationAttempts())
                .lastConfirmationAt(gallery.getKomgaLastConfirmationAt())
                .confirmationReason(gallery.getKomgaConfirmationReason())
                .candidateBookIds(gallery.getKomgaCandidateBookIds())
                .build();
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

    private boolean hasStatus(EhGalleriesEntity gallery, DownloadStatus status) {
        return status.getValue().equals(gallery.getDownloadStatus())
                || status.name().equals(gallery.getDownloadStatus());
    }

    private String normalizeStatus(String status) {
        return switch (status) {
            case "WAITING_KOMGA", "等待 Komga" -> "WAITING_KOMGA";
            case "KOMGA_IMPORT_FAILED", "Komga 入库失败" -> "KOMGA_IMPORT_FAILED";
            case "DOWNLOADED", "已下载" -> "DOWNLOADED";
            default -> null;
        };
    }

    private String statusLabel(String status) {
        return switch (status) {
            case "WAITING_KOMGA" -> "等待 Komga";
            case "KOMGA_IMPORT_FAILED" -> "Komga 入库失败";
            case "DOWNLOADED" -> "已下载";
            default -> status;
        };
    }
}
