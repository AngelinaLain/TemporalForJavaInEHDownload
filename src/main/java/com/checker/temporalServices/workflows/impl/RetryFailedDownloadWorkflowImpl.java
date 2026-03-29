package com.checker.temporalServices.workflows.impl;

import cn.hutool.core.util.StrUtil;
import com.checker.entity.EhGalleriesEntity;
import com.checker.temporalServices.activities.EHAutomationActivity;
import com.checker.temporalServices.workflows.RetryFailedDownloadWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@WorkflowImpl(taskQueues = "EHDownloadTaskQueue")
public class RetryFailedDownloadWorkflowImpl implements RetryFailedDownloadWorkflow {
    private static final Logger log = Workflow.getLogger(RetryFailedDownloadWorkflowImpl.class);
    private final EHAutomationActivity activity = Workflow.newActivityStub(
            EHAutomationActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5)) // 单个 Activity 最大执行时间
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(10))
                            .setMaximumAttempts(3) // 失败最多重试 3 次
                            .setDoNotRetry("QUOTA_EXCEEDED", "IP_BANNED", "ARCHIVE_LINK_EXTRACT_FAILED", "SYNOLOGY_AUTH_FAILED")
                            .build())
                    .build());
    @Override
    public void retryFailedTasks() {
        // 1. 直接从数据库拉取失败的记录，跳过网页搜索！
        List<EhGalleriesEntity> failedGalleries = activity.getFailedGalleries();

        if (failedGalleries == null || failedGalleries.isEmpty()) {
            return;
        }
        List<Promise<Void>> komgaPromises = new ArrayList<>();
        for (EhGalleriesEntity gallery : failedGalleries) {
            String oldStatus = gallery.getDownloadStatus();
            // 🛠️ 阶段 1：断点补偿机制（群晖下完了，但 Komga 没识别/没打上标签）
            if ("已下载".equals(oldStatus)) {
                log.info("🚀 [重试补偿] 画廊已下载但未入库，触发 Komga 抢救机制。GID: {}", gallery.getGid());
                try {
                    // 直接获取元数据并踹一脚 Komga 扫描
                    activity.fetchAndSaveMetadata(gallery.getGid(), gallery.getToken());
                    // 🚀 2. 核心新增：调用物理重命名！
                    EhGalleriesEntity downloadedGallery = activity.getGalleryById(gallery.getGid());
                    if (downloadedGallery != null && StrUtil.isNotBlank(downloadedGallery.getFilename())) {
                        activity.renameSynologyFile(gallery.getGid(), downloadedGallery.getFilename());
                    }
                    activity.triggerKomgaLibraryScan();
                    Promise<Void> komgaTask = Async.procedure(() -> {
                        boolean isImportedToKomga = false;
                        int maxKomgaRetries = 20;
                        int currentTry = 0;
                        while (!isImportedToKomga && currentTry < maxKomgaRetries) {
                            Workflow.sleep(Duration.ofSeconds(15));
                            currentTry++;
                            String komgaSeriesId = activity.findBookInKomga(gallery.getGid());
                            if (komgaSeriesId != null) {
                                activity.pushMetadataToKomga(komgaSeriesId, gallery.getGid());
                                isImportedToKomga = true;
                            }
                        }
                        if (!isImportedToKomga) {
                            log.warn("[⚠️ Komga 补偿入库超时] GID: {} ", gallery.getGid());
                            activity.sendEmailAlert("⚠️ Komga 补偿入库超时", "尝试补偿入库依然失败: " + gallery.getTitle());
                        }
                    });
                    komgaPromises.add(komgaTask);
                } catch (Exception e) {
                    log.error("补偿机制发生异常 GID: " + gallery.getGid(), e);
                }
                // 补偿任务已发出，跳过下方重新拉取直链和推群晖的逻辑！
                continue;
            }
            // 🛠️ 阶段 2：正常的重试逻辑 (针对 "下载失败" 的画廊)
            try {
                // 将状态重置为“下载中”
                activity.updateGalleryStatus(gallery.getGid(), "下载中");

                // 2. 利用数据库里存的 gid 和 token，重新去 EHentai 拿最新的下载直链
                String downloadUrl = activity.extractDownloadUrl(gallery.getGid(), gallery.getToken());

                // 3. 重新推给群晖
                Long gid = activity.pushToSynology(downloadUrl, gallery.getGid(), null);

                boolean isDownloadComplete = false;
                while (!isDownloadComplete) {
                    Workflow.sleep(Duration.ofMinutes(5));
                    String status = activity.checkSynologyTaskStatus(gid, downloadUrl);
                    if ("finished".equalsIgnoreCase(status)) {
                        activity.updateGalleryStatus(gallery.getGid(), "已下载");
                        isDownloadComplete = true;
                        activity.fetchAndSaveMetadata(gallery.getGid(), gallery.getToken());
                        // 🚀 2. 核心新增：调用物理重命名！
                        EhGalleriesEntity downloadedGallery = activity.getGalleryById(gallery.getGid());
                        if (downloadedGallery != null && StrUtil.isNotBlank(downloadedGallery.getFilename())) {
                            activity.renameSynologyFile(gallery.getGid(), downloadedGallery.getFilename());
                        }
                        // Go Work!!!
                        activity.triggerKomgaLibraryScan();
                        Promise<Void> komgaTask = Async.procedure(() -> {

                            boolean isImportedToKomga = false;
                            int maxKomgaRetries = 20;
                            int currentTry = 0;
                            while (!isImportedToKomga && currentTry < maxKomgaRetries) {
                                Workflow.sleep(Duration.ofSeconds(15));
                                currentTry++;
                                // 用真实文件名去搜！
                                String komgaSeriesId = activity.findBookInKomga(gallery.getGid());
                                if (komgaSeriesId != null) {
                                    // 找到后注入元数据和美化标题
                                    activity.pushMetadataToKomga(komgaSeriesId, gallery.getGid());
                                    isImportedToKomga = true;
                                }
                            }
                            if (!isImportedToKomga) {
                                log.warn("[⚠️ Komga 入库超时]画廊已下载，且已触发扫描，但未识别:{} " , gallery.getTitle());
                                activity.sendEmailAlert("⚠️ Komga 入库超时", "画廊已下载，且已触发扫描，但未识别: " + gallery.getTitle());
                                /*activity.updateGalleryStatus(gallery.getGid(), "入库失败");*/
                            }
                        });
                        komgaPromises.add(komgaTask);
                    } else if ("error".equalsIgnoreCase(status)) {
                        activity.updateGalleryStatus(gallery.getGid(), "下载失败");
                        activity.sendEmailAlert("群晖下载异常", "画廊: " + gallery.getTitle() + " 下载失败");
                        break;
                    }
                }

            } catch (ActivityFailure e) {
                Throwable cause = e.getCause();
                if (cause instanceof ApplicationFailure appFailure) {
                    String errorType = appFailure.getType();
                    if ("QUOTA_EXCEEDED".equals(errorType) || "IP_BANNED".equals(errorType) || "ARCHIVE_LINK_EXTRACT_FAILED".equals(errorType)) {
                        activity.updateGalleryStatus(gallery.getGid(), "阻断");
                        activity.sendEmailAlert("EHentai 抓取阻断", "致命错误 (" + errorType + "): " + appFailure.getOriginalMessage());
                        return;
                    }
                }
                // 如果是普通的非致命异常（在重试 3 次后依然失败），比如超时、暂时连不上群晖
                activity.updateGalleryStatus(gallery.getGid(), "下载失败");
            }
        }

        if (!komgaPromises.isEmpty()) {
            Promise.allOf(komgaPromises).get();
        }
        activity.sendEmailAlert("重试流程结束", "本次共重试了 " + failedGalleries.size() + " 个画廊");
    }
}
