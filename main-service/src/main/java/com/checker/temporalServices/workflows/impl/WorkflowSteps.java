package com.checker.temporalServices.workflows.impl;

import cn.hutool.core.util.StrUtil;
import com.checker.common.Constants;
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
                notificationActivity.sendEmailAlert(timeoutSubject, timeoutContent);
            }
        });
    }

    private WorkflowSteps() {}
}
