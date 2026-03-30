package com.checker.temporalServices.workflows.impl;

import com.checker.common.Constants;
import com.checker.common.DownloadStatus;
import com.checker.common.ErrorType;
import com.checker.dto.WorkflowSettings;
import com.checker.entity.EhGalleriesEntity;
import com.checker.temporalServices.activities.DatabaseActivity;
import com.checker.temporalServices.activities.KomgaActivity;
import com.checker.temporalServices.activities.NotificationActivity;
import com.checker.temporalServices.activities.ScraperActivity;
import com.checker.temporalServices.activities.SynologyActivity;
import com.checker.temporalServices.workflows.SingleGalleryDownloadWorkflow;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;

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
    private final KomgaActivity komgaActivity = Workflow.newActivityStub(KomgaActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final NotificationActivity notificationActivity = Workflow.newActivityStub(NotificationActivity.class, WorkflowSteps.DEFAULT_OPTIONS);

    @Override
    public void processSingleGallery(EhGalleriesEntity gallery, boolean compensateOnly, WorkflowSettings settings) {
        // ── 补偿模式：已下载但未入库 ──
        if (compensateOnly) {
            log.info("🚀 [补偿] 画廊已下载但未入库，GID: {}", gallery.getGid());
            try {
                WorkflowSteps.postDownloadKomgaProcess(komgaActivity, databaseActivity, synologyActivity,
                        gallery.getGid(), gallery.getToken());
                WorkflowSteps.buildKomgaImportTask(
                        komgaActivity, notificationActivity, gallery, log,
                        "⚠️ Komga 补偿入库超时", "尝试补偿入库依然失败: " + gallery.getTitle(),
                        settings.getKomgaImportMaxRetries(), settings.getKomgaImportPollIntervalSeconds()
                ).get();
            } catch (Exception e) {
                log.error("补偿机制发生异常 GID: " + gallery.getGid(), e);
            }
            return;
        }

        // ── 完整下载流程 ──
        try {
            // 惰性提取：只在真正执行时才请求直链，避免排队过久导致链接过期
            String downloadUrl = scraperActivity.extractDownloadUrl(gallery.getGid(), gallery.getToken());
            Long gid = synologyActivity.pushToSynology(downloadUrl, gallery.getGid(), null);
            databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOADING.getValue());

            boolean isDownloadComplete = false;
            while (!isDownloadComplete) {
                Workflow.sleep(Duration.ofMinutes(settings.getDownloadPollIntervalMinutes()));
                String status = synologyActivity.checkSynologyTaskStatus(gid, downloadUrl);
                if ("finished".equalsIgnoreCase(status)) {
                    databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOADED.getValue());
                    isDownloadComplete = true;
                    WorkflowSteps.postDownloadKomgaProcess(komgaActivity, databaseActivity, synologyActivity,
                            gallery.getGid(), gallery.getToken());
                    WorkflowSteps.buildKomgaImportTask(
                            komgaActivity, notificationActivity, gallery, log,
                            "⚠️ Komga 入库超时", "画廊已下载，且已触发扫描，但未识别: " + gallery.getTitle(),
                            settings.getKomgaImportMaxRetries(), settings.getKomgaImportPollIntervalSeconds()
                    ).get();
                } else if ("error".equalsIgnoreCase(status)) {
                    databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOAD_FAILED.getValue());
                    notificationActivity.sendEmailAlert("❌ 群晖下载异常", "画廊: " + gallery.getTitle() + " 下载失败");
                    break;
                }
            }
            Workflow.sleep(Duration.ofSeconds(settings.getDownloadCooldownSeconds()));
        } catch (ActivityFailure e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApplicationFailure appFailure) {
                String errorType = appFailure.getType();
                if (ErrorType.QUOTA_EXCEEDED.getCode().equals(errorType) ||
                    ErrorType.IP_BANNED.getCode().equals(errorType) ||
                    ErrorType.ARCHIVE_LINK_EXTRACT_FAILED.getCode().equals(errorType)) {
                    databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.BLOCKED.getValue());
                    notificationActivity.sendEmailAlert(
                            "❌ EHentai 抓取阻断", "致命错误 (" + errorType + "): " + appFailure.getOriginalMessage());
                    // 致命错误：向父工作流传播，使其停止派发新任务
                    throw e;
                }
            }
            databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOAD_FAILED.getValue());
        }
    }
}
