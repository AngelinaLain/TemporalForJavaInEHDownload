package com.checker.temporalServices.workflows.impl;

import com.checker.common.Constants;
import com.checker.common.DownloadStatus;
import com.checker.common.ErrorType;
import com.checker.common.SynologyTaskStatus;
import com.checker.dto.ArchiveDownloadInfo;
import com.checker.dto.SynologyDownloadResult;
import com.checker.dto.WorkflowSettings;
import com.checker.entity.EhGalleriesEntity;
import com.checker.temporalServices.activities.AiActivity;
import com.checker.temporalServices.activities.DatabaseActivity;
import com.checker.temporalServices.activities.KomgaActivity;
import com.checker.temporalServices.activities.LocalImportActivity;
import com.checker.temporalServices.activities.NotificationActivity;
import com.checker.temporalServices.activities.ScraperActivity;
import com.checker.temporalServices.activities.SynologyActivity;
import com.checker.temporalServices.workflows.KomgaImportWorkflow;
import com.checker.temporalServices.workflows.SingleGalleryDownloadWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Optional;

import static io.temporal.internal.sync.AsyncInternal.procedure;

/**
 * 单本画廊下载子工作流实现。
 * <p>
 * 每个子工作流拥有独立的 Temporal 事件历史，sleep 轮询事件被隔离在子工作流内部，
 * 从而避免主工作流历史记录爆炸（突破 50,000 条限制）。
 * <p>
 * 惰性提取 (Lazy Extraction)：只有当画廊真正获得并发令牌、轮到执行时，
 * 才会去请求 archiver.php 获取直链，避免直链因排队过久而过期。
 */
@WorkflowImpl(taskQueues = Constants.TASK_QUEUE)
public class SingleGalleryDownloadWorkflowImpl implements SingleGalleryDownloadWorkflow {
    private static final Logger log = Workflow.getLogger(SingleGalleryDownloadWorkflowImpl.class);

    private final ScraperActivity scraperActivity = Workflow.newActivityStub(ScraperActivity.class, WorkflowSteps.SCRAPER_OPTIONS);
    private final DatabaseActivity databaseActivity = Workflow.newActivityStub(DatabaseActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final SynologyActivity synologyActivity = Workflow.newActivityStub(SynologyActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final SynologyActivity synologyLongPollActivity = Workflow.newActivityStub(SynologyActivity.class, WorkflowSteps.SYNO_LONG_OPTIONS);
    private final KomgaActivity komgaActivity = Workflow.newActivityStub(KomgaActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final NotificationActivity notificationActivity = Workflow.newActivityStub(NotificationActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final AiActivity aiActivity = Workflow.newActivityStub(AiActivity.class, WorkflowSteps.AI_OPTIONS);
    private final LocalImportActivity localImportActivity = Workflow.newActivityStub(LocalImportActivity.class, WorkflowSteps.LOCAL_IMPORT_OPTIONS);

    @Override
    public void processSingleGallery(EhGalleriesEntity gallery, boolean compensateOnly, WorkflowSettings settings) {
        int batchNotificationVersion = Workflow.getVersion(
                "batch-email-notification", Workflow.DEFAULT_VERSION, 1);
        boolean waitForBatchCompletion = batchNotificationVersion != Workflow.DEFAULT_VERSION;

        if (compensateOnly) {
            log.info("🚀 [补偿] 画廊已下载但未入库，GID: {}", gallery.getGid());
            try {
                /*WorkflowSteps.postDownloadKomgaProcess(komgaActivity, databaseActivity, synologyActivity,
                        gallery.getGid(), gallery.getToken());
                WorkflowSteps.buildKomgaImportTask(
                        komgaActivity, notificationActivity, gallery, log,
                        "⚠️ Komga 补偿入库超时", "尝试补偿入库依然失败: " + gallery.getTitle(), 
                        settings.getKomgaImportMaxRetries(), settings.getKomgaImportPollIntervalSeconds()
                ).get();*/
                runKomgaImport(gallery, settings,
                        "⚠️ Komga 入库超时",
                        "未识别或尝试补偿入库失败: " + gallery.getTitle(),
                        waitForBatchCompletion);
            } catch (Exception e) {
                log.error("补偿机制发生异常 GID: " + gallery.getGid(), e);
            }
            return;
        }

        /* 定义 Temporal 原生的重试策略 */
        RetryOptions retryOptions = RetryOptions.newBuilder()
                .setMaximumAttempts(3)
                .setInitialInterval(Duration.ofSeconds(15)) // 每次重试前缓冲 15 秒
                .setBackoffCoefficient(1.0) // 线性重试，不使用指数退避
                // 将账号限额、IP被封、Cookie失效等致命错误标记为”不重试”，一旦抛出立即宣告彻底失败
                .setDoNotRetry(
                        ErrorType.QUOTA_EXCEEDED.getCode(),
                        ErrorType.IP_BANNED.getCode(),
                        ErrorType.ARCHIVE_LINK_EXTRACT_FAILED.getCode(),
                        ErrorType.COOKIE_EXPIRED.getCode()
                )
                .build();

        try {
            /*
            惰性提取直链和文件大小
            使用 Workflow.retry 包装整个“获取直链 -> 推送 -> 轮询”的过程
            */
            Workflow.retry(retryOptions, Optional.empty(), () -> {
                long jitterSeconds = (gallery.getGid() % 10) + 1;
                Workflow.sleep(Duration.ofSeconds(jitterSeconds));
                ArchiveDownloadInfo dowInfo = scraperActivity.extractDownloadUrl(gallery.getGid(), gallery.getToken());
                String downloadUrl = dowInfo.getDownloadUrl();
                double sizeMb = dowInfo.getEstimatedSizeMb();

                databaseActivity.updateGallerySize(gallery.getGid(), sizeMb);

                // 本地模式（默认）：本地下载 + 注入 ComicInfo.xml + 上传群晖，元数据由 Komga 扫描时自动识别
                if (isLocalMode(settings)) {
                    processLocalImport(gallery, downloadUrl, sizeMb, settings, waitForBatchCompletion);
                    return true;
                }

                long estimatedWaitSeconds = Math.max((long) ((sizeMb / 3.0) * 1.1), 30);

                synologyActivity.pushToSynology(downloadUrl, gallery.getGid(), null);
                databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOADING.getValue());

                log.info("预估文件大小 {} MB，把等待与轮询下沉到 Synology 长轮询 Activity（48h 超时 + 5min 心跳）...", sizeMb);
                // 等待 + 轮询整体在 Activity 内部执行，Workflow 历史不再因 sleep 轮询膨胀
                SynologyDownloadResult result = synologyLongPollActivity
                        .waitForDownloadComplete(gallery.getGid(), downloadUrl, estimatedWaitSeconds);

                if (result.getStatus() == SynologyTaskStatus.FINISHED) {
                    // PARTIAL 完整性校验：实际文件大小显著小于预估大小（或与页数严重不符）→ 标记“不完整”待人工审核
                    if (isPartialDownload(sizeMb, result.getActualSizeMb(), gallery)) {
                        databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.PARTIAL.getValue());
                        if (!waitForBatchCompletion) {
                            notificationActivity.sendEmailAlert("⚠️ 下载文件疑似不完整",
                                    "画廊: " + gallery.getTitle()
                                            + "\nGID: " + gallery.getGid()
                                            + "\n预估大小: " + sizeMb + " MB"
                                            + "\n实际大小: " + result.getActualSizeMb() + " MB"
                                            + "\n已标记为 PARTIAL（不完整）");
                        }
                        return true; // 视作本轮流程结束，进入人工审核队列
                    }

                    databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOADED.getValue());

                    runKomgaImport(gallery, settings,
                            "⚠️ Komga 入库超时",
                            "未识别或尝试补偿入库失败: " + gallery.getTitle(),
                            waitForBatchCompletion);

                    return true; // 成功，退出 retry 块
                }
                // SYNOLOGY_DOWNLOAD_ERROR 已列入 doNotRetry，Activity 内部会直接上抛
                throw ApplicationFailure.newFailure("Synology returned error or fake file",
                        ErrorType.SYNOLOGY_DOWNLOAD_ERROR.getCode());
            });
        } catch (TemporalFailure e) {
            // 收尾处理：当重试次数耗尽，或者遇到被 DoNotRetry 拦截的致命异常时，会走到这里
            Throwable cause = e instanceof ActivityFailure ? e.getCause() : e;
            if (cause instanceof ApplicationFailure appFailure) {
                String errorType = appFailure.getType();
                if (ErrorType.QUOTA_EXCEEDED.getCode().equals(errorType) ||
                        ErrorType.IP_BANNED.getCode().equals(errorType) ||
                        ErrorType.COOKIE_EXPIRED.getCode().equals(errorType) ||
                        ErrorType.ARCHIVE_LINK_EXTRACT_FAILED.getCode().equals(errorType)) {
                    databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.BLOCKED.getValue());
                    if (!waitForBatchCompletion) {
                        notificationActivity.sendEmailAlert(
                                "❌ EHentai 抓取阻断", "致命错误: " + appFailure.getOriginalMessage());
                    }
                    throw e; // 抛出异常阻断父工作流
                }
                if (ErrorType.SYNOLOGY_DOWNLOAD_ERROR.getCode().equals(errorType)) {
                    log.error("❌ 拦截到伪装小文件，已直接放弃重试，GID: {}", gallery.getGid());
                    databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOAD_FAILED.getValue());
                    return; // 直接 return 结束当前子工作流，释放并发槽位
                }
            }
            // 无论是 3 次 SYNOLOGY_DOWNLOAD_ERROR 还是 Activity 的网络异常耗尽了次数，都在此处兜底
            log.error("❌ 经过最大次数重试后仍然失败，GID: {}", gallery.getGid());
            databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOAD_FAILED.getValue());
            if (!waitForBatchCompletion) {
                notificationActivity.sendEmailAlert(
                        "❌ 群晖下载异常", "画廊: " + gallery.getTitle() + " 下载连续失败，已达最大重试次数");
            }
        }
    }

    /**
     * 新流程同步等待 Komga 子流程结束，使最外层父流程能在所有层级真正完成后统一发信。
     * 旧流程继续沿用 ABANDON + 异步启动，以兼容已存在的 Temporal 历史。
     */
    private void runKomgaImport(EhGalleriesEntity gallery, WorkflowSettings settings,
                                String timeoutSubject, String timeoutContent,
                                boolean waitForBatchCompletion) {
        ChildWorkflowOptions.Builder options = ChildWorkflowOptions.newBuilder()
                .setWorkflowId("komga-import-" + gallery.getGid());
        if (!waitForBatchCompletion) {
            options.setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON);
        }
        KomgaImportWorkflow komgaWorkflow = Workflow.newChildWorkflowStub(
                KomgaImportWorkflow.class, options.build());

        if (waitForBatchCompletion) {
            komgaWorkflow.waitForImport(
                    gallery,
                    settings.getKomgaImportMaxRetries(),
                    settings.getKomgaImportPollIntervalSeconds(),
                    timeoutSubject,
                    timeoutContent);
            return;
        }

        procedure(() -> komgaWorkflow.waitForImport(
                gallery,
                settings.getKomgaImportMaxRetries(),
                settings.getKomgaImportPollIntervalSeconds(),
                timeoutSubject,
                timeoutContent));
        Promise<WorkflowExecution> executionPromise = Workflow.getWorkflowExecution(komgaWorkflow);
        executionPromise.get();
    }

    /**
     * 是否使用本地下载 + ComicInfo 注入模式。
     * 默认（未显式配置 downloadstation）即为本地模式。
     */
    private static boolean isLocalMode(WorkflowSettings settings) {
        return !"downloadstation".equalsIgnoreCase(settings.getDownloadMode());
    }

    /**
     * 本地导入模式：
     * <ol>
     *   <li>拉取 EHentai 标签 + AI 生成简介（供 ComicInfo 注入；失败不阻塞下载）；</li>
     *   <li>本地下载 + 注入 ComicInfo.xml + 上传群晖（长 Activity，48h 超时 + 心跳）；</li>
     *   <li>进入 WAITING_KOMGA，复用 Komga 子工作流确认唯一 BookID 后再标记 IMPORTED。</li>
     * </ol>
     */
    private void processLocalImport(EhGalleriesEntity gallery, String downloadUrl, double sizeMb,
                                    WorkflowSettings settings, boolean waitForBatchCompletion) {
        // 元数据 / AI 简介获取失败不阻塞下载，ComicInfo 部分字段缺省仍可入库
        try {
            komgaActivity.fetchAndSaveMetadata(gallery.getGid(), gallery.getToken());
            EhGalleriesEntity withTags = databaseActivity.getGalleryById(gallery.getGid());
            if (withTags != null && withTags.getTags() != null && !withTags.getTags().isEmpty()) {
                String summary = aiActivity.generateGallerySummary(gallery.getTitle(), withTags.getTags());
                if (summary != null && !summary.isBlank()) {
                    databaseActivity.updateGallerySummary(gallery.getGid(), summary);
                    log.info("🤖 AI 简介生成成功, GID: {}", gallery.getGid());
                }
            }
        } catch (Exception metaEx) {
            log.warn("⚠️ 元数据/AI 简介获取失败，继续下载（ComicInfo 部分字段缺省）, GID: {}, 原因: {}",
                    gallery.getGid(), metaEx.getMessage());
        }

        databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOADING.getValue());
        log.info("🚀 本地下载 + ComicInfo 注入模式，GID: {}, 预估 {} MB", gallery.getGid(), sizeMb);

        // 下载 → 注入 ComicInfo.xml → 重命名 .cbz → 上传群晖（内部带心跳）
        localImportActivity.localDownloadAndImport(downloadUrl, gallery.getGid(), sizeMb);

        int confirmationVersion = Workflow.getVersion(
                "local-komga-import-confirmation", Workflow.DEFAULT_VERSION, 1);
        if (confirmationVersion == Workflow.DEFAULT_VERSION) {
            // 兼容已经运行的旧 Temporal 历史。
            komgaActivity.triggerKomgaLibraryScan();
            databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.IMPORTED.getValue());
            return;
        }

        databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.WAITING_KOMGA.getValue());
        runKomgaImport(gallery, settings,
                "⚠️ Komga 入库超时",
                "本地上传完成，但 Komga 未确认入库: " + gallery.getTitle(),
                waitForBatchCompletion);
    }

    /**
     * PARTIAL 完整性校验：
     * <ol>
     *   <li>实际文件大小显著小于预估大小（不足 60%），判定下载不完整；</li>
     *   <li>若已知页数，则校验平均每页大小是否低于 100KB（漫画单页通常 ≥ 300KB），
     *       明显偏低说明压缩包内容缺失。</li>
     * </ol>
     * 任一条件满足即标记 PARTIAL，进入人工审核队列。
     */
    private static boolean isPartialDownload(double expectedSizeMb, Double actualSizeMb, EhGalleriesEntity gallery) {
        if (actualSizeMb == null || actualSizeMb <= 0) {
            return false;
        }
        // 大小比值校验：实际不足预估 60%
        if (expectedSizeMb > 0 && actualSizeMb < expectedSizeMb * 0.6) {
            log.warn("⚠️ 大小校验失败：实际 {} MB 远小于预估 {} MB，标记 PARTIAL", actualSizeMb, expectedSizeMb);
            return true;
        }
        // 页数校验：平均每页小于 100KB 视为异常
        Integer pageCount = gallery.getPageCount();
        if (pageCount != null && pageCount > 0) {
            double avgKbPerPage = actualSizeMb * 1024.0 / pageCount;
            if (avgKbPerPage < 100) {
                log.warn("⚠️ 页数校验失败：平均每页 {:.1f} KB（{} MB / {} 页）偏低，标记 PARTIAL",
                        avgKbPerPage, actualSizeMb, pageCount);
                return true;
            }
        }
        return false;
    }
}
