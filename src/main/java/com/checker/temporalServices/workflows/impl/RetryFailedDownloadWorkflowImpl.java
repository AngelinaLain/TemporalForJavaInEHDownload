package com.checker.temporalServices.workflows.impl;

import com.checker.common.DownloadStatus;
import com.checker.common.ErrorType;
import com.checker.entity.EhGalleriesEntity;
import com.checker.temporalServices.activities.DatabaseActivity;
import com.checker.temporalServices.activities.KomgaActivity;
import com.checker.temporalServices.activities.NotificationActivity;
import com.checker.temporalServices.activities.ScraperActivity;
import com.checker.temporalServices.activities.SynologyActivity;
import com.checker.temporalServices.workflows.RetryFailedDownloadWorkflow;
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
 * 重试失败下载工作流实现：查询失败画廊并重新执行下载、入库流程
 */
@WorkflowImpl(taskQueues = "EHDownloadTaskQueue")
public class RetryFailedDownloadWorkflowImpl implements RetryFailedDownloadWorkflow {
    private static final Logger log = Workflow.getLogger(RetryFailedDownloadWorkflowImpl.class);

        private final ScraperActivity scraperActivity = Workflow.newActivityStub(ScraperActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
        private final DatabaseActivity databaseActivity = Workflow.newActivityStub(DatabaseActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
        private final SynologyActivity synologyActivity = Workflow.newActivityStub(SynologyActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
        private final KomgaActivity komgaActivity = Workflow.newActivityStub(KomgaActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
        private final NotificationActivity notificationActivity = Workflow.newActivityStub(NotificationActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    @Override
    public void retryFailedTasks() {
        List<EhGalleriesEntity> failedGalleries = databaseActivity.getFailedGalleries();
        if (failedGalleries == null || failedGalleries.isEmpty()) {
            return;
        }
        List<Promise<Void>> komgaPromises = new ArrayList<>();
        for (EhGalleriesEntity gallery : failedGalleries) {
            String oldStatus = gallery.getDownloadStatus();
            // 阶段 1：断点补偿（已下载但未入库）
            if (DownloadStatus.DOWNLOADED.getValue().equals(oldStatus)) {
                log.info("🚀 [重试补偿] 画廊已下载但未入库。GID: {}", gallery.getGid());
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
            // 阶段 2：正常重试（下载失败的画廊）
            try {
                databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOADING.getValue());
                String downloadUrl = scraperActivity.extractDownloadUrl(gallery.getGid(), gallery.getToken());
                Long gid = synologyActivity.pushToSynology(downloadUrl, gallery.getGid(), null);

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
                        notificationActivity.sendEmailAlert("群晖下载异常", "画廊: " + gallery.getTitle() + " 下载失败");
                        break;
                    }
                }
            } catch (ActivityFailure e) {
                Throwable cause = e.getCause();
                if (cause instanceof ApplicationFailure appFailure) {
                    String errorType = appFailure.getType();
                    if (ErrorType.QUOTA_EXCEEDED.getCode().equals(errorType) || ErrorType.IP_BANNED.getCode().equals(errorType) || ErrorType.ARCHIVE_LINK_EXTRACT_FAILED.getCode().equals(errorType)) {
                        databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.BLOCKED.getValue());
                        notificationActivity.sendEmailAlert("EHentai 抓取阻断", "致命错误 (" + errorType + "): " + appFailure.getOriginalMessage());
                        return;
                    }
                }
                databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOAD_FAILED.getValue());
            }
        }
        if (!komgaPromises.isEmpty()) {
            Promise.allOf(komgaPromises).get();
        }
        notificationActivity.sendEmailAlert("重试流程结束", "本次共重试了 " + failedGalleries.size() + " 个画廊");
    }
}
