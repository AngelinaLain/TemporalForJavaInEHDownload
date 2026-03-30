package com.checker.temporalServices.workflows.impl;

import com.checker.dto.SearchOptions;
import com.checker.entity.EhGalleriesEntity;
import com.checker.temporalServices.activities.DatabaseActivity;
import com.checker.temporalServices.activities.KomgaActivity;
import com.checker.temporalServices.activities.NotificationActivity;
import com.checker.temporalServices.activities.ScraperActivity;
import com.checker.temporalServices.activities.SynologyActivity;
import com.checker.temporalServices.workflows.EHAutomationWorkflow;
import com.checker.common.DownloadStatus;
import com.checker.common.ErrorType;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * EHentai 自动化主工作流实现：编排爬虫→入库→下载→Komga导入全流程
 */
@WorkflowImpl(taskQueues = "EHDownloadTaskQueue")
public class EHAutomationWorkflowImpl implements EHAutomationWorkflow {
    private static final Logger log = Workflow.getLogger(EHAutomationWorkflowImpl.class);

    private final ScraperActivity scraperActivity = Workflow.newActivityStub(ScraperActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final DatabaseActivity databaseActivity = Workflow.newActivityStub(DatabaseActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final SynologyActivity synologyActivity = Workflow.newActivityStub(SynologyActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final KomgaActivity komgaActivity = Workflow.newActivityStub(KomgaActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final NotificationActivity notificationActivity = Workflow.newActivityStub(NotificationActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    @Override
    public void executeAutomation(SearchOptions searchOptions) {
        List<EhGalleriesEntity> galleries = scraperActivity.scrapeGalleries(searchOptions);
        if (galleries == null || galleries.isEmpty()) {
            return;
        }
        List<Promise<Void>> komgaPromises = new ArrayList<>();
        for (EhGalleriesEntity gallery : galleries) {
            EhGalleriesEntity existingGallery = databaseActivity.getGalleryById(gallery.getGid());
            if (existingGallery != null) {
                String oldStatus = existingGallery.getDownloadStatus();
                if (DownloadStatus.IMPORTED.getValue().equals(oldStatus) || DownloadStatus.DOWNLOADING.getValue().equals(oldStatus) || DownloadStatus.BLOCKED.getValue().equals(oldStatus)) {
                    log.info("⏭️ 画廊已被处理过，状态为: {}，跳过。GID: {}", oldStatus, gallery.getGid());
                    continue;
                }
                // 断点补偿：已下载但未入库
                if (DownloadStatus.DOWNLOADED.getValue().equals(oldStatus)) {
                    log.info("🚀 画廊已下载但未入库，触发 Komga 补偿机制。GID: {}", gallery.getGid());
                    try {
                        WorkflowSteps.postDownloadKomgaProcess(komgaActivity, databaseActivity, synologyActivity, gallery.getGid(), gallery.getToken());
                        komgaPromises.add(WorkflowSteps.buildKomgaImportTask(
                                komgaActivity, notificationActivity, gallery, log,
                                "⚠️ Komga 补偿入库超时", "尝试补偿入库依然失败: " + gallery.getTitle()));
                    } catch (Exception e) {
                        log.error("补偿机制发生异常 GID: " + gallery.getGid(), e);
                    }
                    continue;
                }
            }

            databaseActivity.saveToDatabase(gallery);
            try {
                String downloadUrl = scraperActivity.extractDownloadUrl(gallery.getGid(), gallery.getToken());
                Long gid = synologyActivity.pushToSynology(downloadUrl, gallery.getGid(), null);
                databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOADING.getValue());

                boolean isDownloadComplete = false;
                while (!isDownloadComplete) {
                    Workflow.sleep(Duration.ofMinutes(5));
                    String status = synologyActivity.checkSynologyTaskStatus(gid, downloadUrl);
                    if ("finished".equalsIgnoreCase(status)) {
                        databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOADED.getValue());
                        isDownloadComplete = true;
                        WorkflowSteps.postDownloadKomgaProcess(komgaActivity, databaseActivity, synologyActivity, gallery.getGid(), gallery.getToken());
                        komgaPromises.add(WorkflowSteps.buildKomgaImportTask(
                                komgaActivity, notificationActivity, gallery, log,
                                "⚠️ Komga 入库超时", "画廊已下载，且已触发扫描，但未识别: " + gallery.getTitle()));
                    } else if ("error".equalsIgnoreCase(status)) {
                        databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOAD_FAILED.getValue());
                        notificationActivity.sendEmailAlert("❌ 群晖下载异常", "画廊: " + gallery.getTitle() + " 下载失败");
                        break;
                    }
                }
                Workflow.sleep(Duration.ofSeconds(10));
            } catch (ActivityFailure e) {
                Throwable cause = e.getCause();
                if (cause instanceof ApplicationFailure appFailure) {
                    String errorType = appFailure.getType();
                    if (
                            ErrorType.QUOTA_EXCEEDED.getCode().equals(errorType) ||
                            ErrorType.IP_BANNED.getCode().equals(errorType) ||
                            ErrorType.ARCHIVE_LINK_EXTRACT_FAILED.getCode().equals(errorType)
                    ) {
                        databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.BLOCKED.getValue());
                        notificationActivity.sendEmailAlert(
                                "❌ EHentai 抓取阻断", "致命错误 (" + errorType + "): " + appFailure.getOriginalMessage());
                        return;
                    }
                }
                databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOAD_FAILED.getValue());
            }
        }
        if (!komgaPromises.isEmpty()) {
            Promise.allOf(komgaPromises).get();
        }
        notificationActivity.sendEmailAlert("抓取流程结束", "本次共处理 " + galleries.size() + " 个画廊");
    }
}
