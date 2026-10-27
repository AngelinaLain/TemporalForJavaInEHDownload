package com.checker.temporalServices.workflows.impl;

import cn.hutool.core.util.StrUtil;
import com.checker.common.Constants;
import com.checker.common.DownloadStatus;
import com.checker.common.ErrorType;
import com.checker.entity.EhGalleriesEntity;
import com.checker.temporalServices.activities.DatabaseActivity;
import com.checker.temporalServices.activities.KomgaActivity;
import com.checker.temporalServices.activities.NotificationActivity;
import com.checker.temporalServices.activities.SynologyActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 集中定义工作流使用的活动队列、超时与重试策略，以及汇总通知和旧历史兼容步骤。
 * Komga 轮询次数和间隔由调用方传入的运行设置决定，不在工具类中写死。
 */
class WorkflowSteps {


    /** 普通数据库、通知和图库操作使用的超时与重试策略。 */
    static final ActivityOptions DEFAULT_OPTIONS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(10))
                    .setMaximumAttempts(3)
                    .setDoNotRetry(
                            ErrorType.QUOTA_EXCEEDED.getCode(),
                            ErrorType.IP_BANNED.getCode(),
                            ErrorType.ARCHIVE_LINK_EXTRACT_FAILED.getCode(),
                            ErrorType.SYNOLOGY_AUTH_FAILED.getCode(),
                            ErrorType.COOKIE_EXPIRED.getCode()
                    ).build()).build();

    /**
     * 将 AI 推理派发到专用队列，并为模型响应留出执行时间。
     * 尝试次数包含首次调用，避免长时间模型故障阻塞整个入库流程。
     */
    static final ActivityOptions AI_OPTIONS = ActivityOptions.newBuilder()
            .setTaskQueue(Constants.AI_TASK_QUEUE)
            .setScheduleToCloseTimeout(Duration.ofMinutes(4))
            .setStartToCloseTimeout(Duration.ofMinutes(3))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(15))
                    .setMaximumAttempts(2)
                    .build())
            .build();

    /**
     * 抓取操作使用更长超时和心跳检查，临时失败时逐步延长重试间隔。
     * 登录凭据失效、配额超限或 IP 封禁等问题不自动重试，交给工作流记录并通知处理。
     */
    static final ActivityOptions SCRAPER_OPTIONS = ActivityOptions.newBuilder()
            .setTaskQueue(Constants.SCRAPER_TASK_QUEUE)
            .setStartToCloseTimeout(Duration.ofMinutes(15))
            .setHeartbeatTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(30))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofMinutes(30))
                    .setMaximumAttempts(5)
                    .setDoNotRetry(
                            ErrorType.QUOTA_EXCEEDED.getCode(),
                            ErrorType.IP_BANNED.getCode(),
                            ErrorType.ARCHIVE_LINK_EXTRACT_FAILED.getCode(),
                            ErrorType.SYNOLOGY_AUTH_FAILED.getCode(),
                            ErrorType.COOKIE_EXPIRED.getCode()
                    ).build())
            .build();

    /**
     * 下载等待与状态轮询在一个长时间活动内完成，并通过心跳检查执行状态，
     * 避免把每次等待都写进工作流历史；具体时间限制以此配置为准。
     */
    static final ActivityOptions SYNO_LONG_OPTIONS = ActivityOptions.newBuilder()
            .setTaskQueue(Constants.TASK_QUEUE)
            .setStartToCloseTimeout(Duration.ofHours(48))
            .setHeartbeatTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(15))
                    .setMaximumAttempts(3)
                    .setDoNotRetry(
                            ErrorType.QUOTA_EXCEEDED.getCode(),
                            ErrorType.IP_BANNED.getCode(),
                            ErrorType.ARCHIVE_LINK_EXTRACT_FAILED.getCode(),
                            ErrorType.SYNOLOGY_AUTH_FAILED.getCode(),
                            ErrorType.COOKIE_EXPIRED.getCode(),
                            ErrorType.SYNOLOGY_DOWNLOAD_ERROR.getCode()
                    ).build())
            .build();

    /**
     * 本地大文件导入允许长时间执行，并持续检查心跳。
     * 下载器内部处理网络重试，外层工作流负责刷新过期直链；本层不再重复叠加尝试次数。
     */
    static final ActivityOptions LOCAL_IMPORT_OPTIONS = ActivityOptions.newBuilder()
            .setTaskQueue(Constants.TASK_QUEUE)
            .setStartToCloseTimeout(Duration.ofHours(48))
            .setHeartbeatTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(15))
                    .setMaximumAttempts(1)
                    .setDoNotRetry(
                            ErrorType.QUOTA_EXCEEDED.getCode(),
                            ErrorType.IP_BANNED.getCode(),
                            ErrorType.ARCHIVE_LINK_EXTRACT_FAILED.getCode(),
                            ErrorType.SYNOLOGY_AUTH_FAILED.getCode(),
                            ErrorType.COOKIE_EXPIRED.getCode()
                    ).build())
            .build();

    /**
     * 在父工作流等待全部已启动的子工作流结束后，按数据库最终状态生成一封汇总邮件。
     * 这里只拼装确定性文本，不执行任何外部 I/O，适合在 Workflow 代码中调用。
     */
    static String buildBatchNotificationContent(String flowName, int discovered, int planned, int started,
                                                boolean stoppedByFatalError,
                                                List<EhGalleriesEntity> finalStates) {
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        for (DownloadStatus status : DownloadStatus.values()) {
            statusCounts.put(status.getValue(), 0);
        }
        int unknown = 0;
        if (finalStates != null) {
            for (EhGalleriesEntity gallery : finalStates) {
                String normalized = normalizeDownloadStatus(gallery.getDownloadStatus());
                if (normalized == null) {
                    unknown++;
                } else {
                    statusCounts.computeIfPresent(normalized, (key, count) -> count + 1);
                }
            }
        }

        int stateRecords = finalStates == null ? 0 : finalStates.size();
        int notStarted = Math.max(planned - started, 0);
        StringBuilder content = new StringBuilder()
                .append(flowName).append("已结束，全部已启动子流程均已完成。\n")
                .append("本次发现: ").append(discovered).append(" 个画廊\n")
                .append("计划子流程: ").append(planned).append(" 个\n")
                .append("实际启动: ").append(started).append(" 个\n")
                .append("未启动: ").append(notStarted).append(" 个\n")
                .append("最终状态记录: ").append(stateRecords).append(" 条\n\n")
                .append("状态汇总:\n");

        statusCounts.forEach((status, count) -> {
            if (count > 0) {
                content.append("- ").append(status).append(": ").append(count).append("\n");
            }
        });
        if (unknown > 0) {
            content.append("- 未知状态: ").append(unknown).append("\n");
        }
        if (stateRecords < planned) {
            content.append("- 未查询到状态记录: ").append(planned - stateRecords).append("\n");
        }
        if (stoppedByFatalError) {
            content.append("\n⚠️ 检测到致命错误，后续尚未启动的子流程已停止派发。");
        }
        return content.toString().trim();
    }

    /** 将旧中文状态和枚举名称统一成展示名称，供批量通知统计。 */
    private static String normalizeDownloadStatus(String value) {
        if (value == null) return null;
        for (DownloadStatus status : DownloadStatus.values()) {
            if (status.getValue().equals(value) || status.name().equals(value)) {
                return status.getValue();
            }
        }
        return null;
    }

    /**
     * 保留旧流程的下载后处理入口：补标签、重命名文件，再触发图库扫描。
     * 新流程由独立的 Komga 入库子流程负责确认和记录结果。
     */
    @Deprecated(since = "0.1", forRemoval = false)
    static void postDownloadKomgaProcess(
            KomgaActivity komgaActivity,
            DatabaseActivity databaseActivity,
            SynologyActivity synologyActivity,
            Long gid, String token) {
        komgaActivity.fetchAndSaveMetadata(gid, token);
        EhGalleriesEntity downloaded = databaseActivity.getGalleryById(gid);
        if (downloaded != null && StrUtil.isNotBlank(downloaded.getFilename())) {
            synologyActivity.renameSynologyFile(gid, downloaded.getFilename());
        }
        komgaActivity.triggerKomgaLibraryScan();
    }

    /**
     * 保留旧流程的异步入库等待入口，按传入设置决定轮询次数与间隔。
     * 旧历史保留单条超时通知，新流程由父工作流统一汇总。
     *
     * @param gallery            目标画廊实体（需要 gid、title）
     * @param timeoutSubject     超时邮件主题
     * @param timeoutContent     超时邮件正文
     * @param maxRetries         最大轮询次数（来自 WorkflowSettings）
     * @param pollIntervalSeconds 轮询间隔秒数（来自 WorkflowSettings）
     * @return Promise&lt;Void&gt; 供调用方加入 {@code Promise.allOf()} 等待
     */
    @Deprecated(since = "0.1", forRemoval = false)
    static Promise<Void> buildKomgaImportTask(
            KomgaActivity komgaActivity,
            NotificationActivity notificationActivity,
            EhGalleriesEntity gallery,
            Logger log,
            String timeoutSubject,
            String timeoutContent,
            int maxRetries,
            int pollIntervalSeconds) {
        int batchNotificationVersion = Workflow.getVersion(
                "batch-email-notification", Workflow.DEFAULT_VERSION, 1);
        return Async.procedure(() -> {
            boolean isImportedToKomga = false;
            int currentTry = 0;
            while (!isImportedToKomga && currentTry < maxRetries) {
                Workflow.sleep(Duration.ofSeconds(pollIntervalSeconds));
                currentTry++;
                String komgaSeriesId = komgaActivity.findBookInKomga(gallery.getGid());
                if (komgaSeriesId != null) {
                    komgaActivity.pushMetadataToKomga(komgaSeriesId, gallery.getGid());
                    isImportedToKomga = true;
                }
            }
            if (!isImportedToKomga) {
                log.warn("[⚠️ Komga 入库超时] GID: {}, 标题: {}", gallery.getGid(), gallery.getTitle());
                if (batchNotificationVersion == Workflow.DEFAULT_VERSION) {
                    notificationActivity.sendEmailAlert(timeoutSubject, timeoutContent);
                }
            }
        });
    }

    private WorkflowSteps() {}
}
