package com.checker.service.komga;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.checker.shared.DownloadStatus;
import com.checker.config.workflow.EhWorkflowConfig;
import com.checker.dao.catalog.EhGalleriesMapper;
import com.checker.entity.EhGalleriesEntity;
import com.checker.model.dto.workflow.WorkflowSettings;
import com.checker.model.vo.komga.KomgaImportReviewItem;
import com.checker.service.workflow.SingleGalleryDownloadWorkflow;
import com.checker.shared.Constants;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Komga 入库复核业务：分页读取待复核记录，并支持只重试扫描确认而不重新下载归档。
 *
 * <p>状态归一、记录认领与工作流启动都在本服务内完成，Web 层只负责把结果转换成响应码。</p>
 */
@Service
public class KomgaImportReviewService {

    private final EhGalleriesMapper galleriesMapper;
    private final WorkflowClient workflowClient;
    private final EhWorkflowConfig workflowConfig;

    public KomgaImportReviewService(EhGalleriesMapper galleriesMapper,
                                    WorkflowClient workflowClient,
                                    EhWorkflowConfig workflowConfig) {
        this.galleriesMapper = galleriesMapper;
        this.workflowClient = workflowClient;
        this.workflowConfig = workflowConfig;
    }

    /**
     * 分页读取复核记录，同时返回失败与等待数量，兼容中英文历史状态。
     *
     * @throws IllegalArgumentException 复核状态不受支持时抛出
     */
    public Map<String, Object> listReviews(int page, int size, String status) {
        QueryWrapper<EhGalleriesEntity> query = new QueryWrapper<>();
        if (!"ALL".equalsIgnoreCase(status)) {
            String normalized = normalizeStatus(status);
            if (normalized == null) {
                throw new IllegalArgumentException("不支持的 Komga 复核状态");
            }
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
        return payload;
    }

    /**
     * 先按当前状态认领画廊，再启动只做 Komga 确认的工作流；启动失败时还原为可重试状态。
     */
    public RetryOutcome retry(Long gid) {
        EhGalleriesEntity gallery = galleriesMapper.selectById(gid);
        if (gallery == null) {
            return RetryOutcome.failed(RetryStatus.NOT_FOUND, "画廊记录不存在");
        }
        if (!hasStatus(gallery, DownloadStatus.KOMGA_IMPORT_FAILED)
                && !hasStatus(gallery, DownloadStatus.DOWNLOADED)) {
            return RetryOutcome.failed(RetryStatus.CONFLICT, "当前状态不允许 Komga 补偿: " + gallery.getDownloadStatus());
        }

        UpdateWrapper<EhGalleriesEntity> claim = new UpdateWrapper<>();
        claim.eq("gid", gid)
                .and(wrapper -> wrapper.eq("download_status", DownloadStatus.KOMGA_IMPORT_FAILED.getValue())
                        .or().eq("download_status", DownloadStatus.KOMGA_IMPORT_FAILED.name())
                        .or().eq("download_status", DownloadStatus.DOWNLOADED.getValue())
                        .or().eq("download_status", DownloadStatus.DOWNLOADED.name()))
                .set("download_status", DownloadStatus.WAITING_KOMGA.getValue());
        if (galleriesMapper.update(null, claim) != 1) {
            return RetryOutcome.failed(RetryStatus.CONFLICT, "该画廊已被其他补偿任务处理");
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
            return RetryOutcome.started(Map.of(
                    "gid", String.valueOf(gid),
                    "workflowId", execution.getWorkflowId(),
                    "runId", execution.getRunId()));
        } catch (RuntimeException exception) {
            UpdateWrapper<EhGalleriesEntity> rollback = new UpdateWrapper<>();
            rollback.eq("gid", gid).set("download_status", DownloadStatus.KOMGA_IMPORT_FAILED.getValue());
            galleriesMapper.update(null, rollback);
            return RetryOutcome.failed(RetryStatus.START_FAILED, "启动 Komga 补偿流程失败: " + exception.getMessage());
        }
    }

    /**
     * 统计某种入库状态的记录数量，同时兼容中文和英文存储值。
     */
    private long countByStatus(DownloadStatus status) {
        QueryWrapper<EhGalleriesEntity> query = new QueryWrapper<>();
        query.and(wrapper -> wrapper.eq("download_status", status.getValue())
                .or().eq("download_status", status.name()));
        return galleriesMapper.selectCount(query);
    }

    /**
     * 把画廊转换成复核列表项，只返回页面需要的字段。
     */
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

    /**
     * 生成本次入库补偿使用的轮询与重试参数。
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
     * 判断一条记录是否处于指定状态，兼容历史中英文值。
     */
    private boolean hasStatus(EhGalleriesEntity gallery, DownloadStatus status) {
        return status.getValue().equals(gallery.getDownloadStatus())
                || status.name().equals(gallery.getDownloadStatus());
    }

    /**
     * 把支持的中文或英文复核状态统一成内部状态名称。
     */
    private String normalizeStatus(String status) {
        return switch (status) {
            case "WAITING_KOMGA", "等待 Komga" -> "WAITING_KOMGA";
            case "KOMGA_IMPORT_FAILED", "Komga 入库失败" -> "KOMGA_IMPORT_FAILED";
            case "DOWNLOADED", "已下载" -> "DOWNLOADED";
            default -> null;
        };
    }

    /**
     * 获取内部复核状态对应的中文存储名称。
     */
    private String statusLabel(String status) {
        return switch (status) {
            case "WAITING_KOMGA" -> "等待 Komga";
            case "KOMGA_IMPORT_FAILED" -> "Komga 入库失败";
            case "DOWNLOADED" -> "已下载";
            default -> status;
        };
    }

    /**
     * 补偿请求的处理结果，供 Web 层决定返回的业务状态码。
     */
    public enum RetryStatus {
        /** 已认领并成功启动补偿工作流 */
        STARTED,
        /** 画廊记录不存在 */
        NOT_FOUND,
        /** 当前状态不允许补偿或已被其他任务认领 */
        CONFLICT,
        /** 工作流启动失败，状态已回滚 */
        START_FAILED
    }

    /**
     * 补偿结果：成功时携带工作流标识，失败时携带提示信息。
     */
    public record RetryOutcome(RetryStatus status, String message, Map<String, String> payload) {

        static RetryOutcome started(Map<String, String> payload) {
            return new RetryOutcome(RetryStatus.STARTED, null, payload);
        }

        static RetryOutcome failed(RetryStatus status, String message) {
            return new RetryOutcome(status, message, Map.of());
        }
    }
}
