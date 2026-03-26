package com.checker.temporalServices.workflows.impl;

import com.checker.entity.EhGalleriesEntity;
import com.checker.temporalServices.activities.EHAutomationActivity;
import com.checker.temporalServices.workflows.RetryFailedDownloadWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.List;

@WorkflowImpl(taskQueues = "EHDownloadTaskQueue")
public class RetryFailedDownloadWorkflowImpl implements RetryFailedDownloadWorkflow {
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

        for (EhGalleriesEntity gallery : failedGalleries) {
            try {
                // 将状态重置为“下载中”
                activity.updateGalleryStatus(gallery.getGid(), "下载中");

                // 2. 利用数据库里存的 gid 和 token，重新去 EHentai 拿最新的下载直链
                String downloadUrl = activity.extractDownloadUrl(gallery.getGid(), gallery.getToken());

                // 3. 重新推给群晖
                Long gid = activity.pushToSynology(downloadUrl, gallery.getGid(), "n8n_bot/EHentai");

                boolean isDownloadComplete = false;
                while (!isDownloadComplete) {
                    Workflow.sleep(Duration.ofMinutes(5));
                    String status = activity.checkSynologyTaskStatus(gid, downloadUrl);
                    if ("finished".equalsIgnoreCase(status)) {
                        activity.updateGalleryStatus(gallery.getGid(), "已下载");
                        isDownloadComplete = true;
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
        activity.sendEmailAlert("重试流程结束", "本次共重试了 " + failedGalleries.size() + " 个画廊");
    }
}
