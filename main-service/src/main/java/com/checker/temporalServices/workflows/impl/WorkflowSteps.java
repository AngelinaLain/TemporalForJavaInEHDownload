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
 * Temporal 工作流共享工具类 — 非 Spring Bean，包内可见。
 * 统一配置类
 * <p>
 * 集中管理：
 * <ul>
 *   <li>统一的 ActivityOptions 配置</li>
 *   <li>下载完成后的 Komga 入库前置三步骤（抓取元数据 → 文件重命名 → 触发扫描）</li>
 *   <li>Komga 异步轮询入库任务构建（20 次重试 × 15 秒）</li>
 * </ul>
 */
class WorkflowSteps {


    /** 所有 Activity Stub 共用的超时与重试策略 */
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
     * AI Activity 专用配置：显式指定 EH_TASK_QUEUE（AiActivityImpl 注册在该队列），
     * 给 LLM 推理留出足够的超时时间，失败最多重试 2 次。
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
     * 爬虫 Activity 专用配置：更长超时 + 心跳检测 + 指数退避重试
     * <p>
     * 指数退避策略：初始 30s → 60s → 120s → ... → 最大 30min
     * Cookie 失效、配额超限、IP 封禁等致命错误直接标记为不可重试，交由 Workflow 层通知人工介入。
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
     * 群晖长轮询 Activity 专用配置：把下载等待与状态轮询整体下沉到 Activity 内部，
     * 48 小时 StartToClose + 5 分钟心跳，避免 Workflow 层 sleep 轮询占用事件历史。
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
     * 本地导入 Activity 专用配置：本地下载大文件可能耗时极长，
     * 48 小时 StartToClose + 5 分钟心跳。下载器内部已处理连接级重试，外层 Workflow.retry
     * 还会重新提取过期直链，因此 Activity 本身不再叠加重试，避免 3×3 放大为 9 次执行。
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
     * 暂时没有用到此函数的地方
     * -------
     * 下载完成后的 Komga 入库前置三步骤：
     * <ol>
     *   <li>向 EHentai 请求标签数据并写入数据库</li>
     *   <li>若数据库已存有文件名，则重命名群晖文件</li>
     *   <li>触发 Komga 库扫描</li>
     * </ol>
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
     * 暂时没有用到此函数的地方
     * -------
     * 构建 Komga 异步轮询入库任务。
     * 超时后通过 {@code notificationActivity} 发送邮件告警。
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
