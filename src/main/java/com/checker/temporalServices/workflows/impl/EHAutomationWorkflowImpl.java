package com.checker.temporalServices.workflows.impl;

import com.checker.dto.SearchOptions;
import com.checker.entity.EhGalleriesEntity;
import com.checker.temporalServices.activities.EHAutomationActivity;
import com.checker.temporalServices.workflows.EHAutomationWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.List;

// 绑定到 application.yaml 中配置的 Task Queue
@WorkflowImpl(taskQueues = "EHDownloadTaskQueue")
public class EHAutomationWorkflowImpl implements EHAutomationWorkflow {
    // 1. 初始化 Activity 的存根 (Stub)，并配置超时与重试策略
    private final EHAutomationActivity activity = Workflow.newActivityStub(
            EHAutomationActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5)) // 单个 Activity 最大执行时间
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(10))
                            .setMaximumAttempts(3) // 失败最多重试 3 次
                            .setDoNotRetry("QUOTA_EXCEEDED", "IP_BANNED", "ARCHIVE_LINK_EXTRACT_FAILED", "SYNOLOGY_AUTH_FAILED")
                            .build())
                    .build()
    );
    @Override
    public void executeAutomation(SearchOptions searchOptions) {
        // 2. 调用 Activity 抓取画廊列表
        List<EhGalleriesEntity> galleries = activity.scrapeGalleries(searchOptions);
        if (galleries == null || galleries.isEmpty()) {
            return; // 没抓到数据，流程结束
        }
        for (EhGalleriesEntity gallery : galleries) {
            // 3. 存入数据库
            activity.saveToDatabase(gallery);
            try {
                // 4. 获取下载直链
                String downloadUrl = activity.extractDownloadUrl(gallery.getGid(), gallery.getToken());
                // 5. 推送给群晖，返回 GID（用于后续任务追踪）
                Long gid = activity.pushToSynology(downloadUrl, gallery.getGid(), "n8n_bot/EHentai");
                // 更新状态为：下载中
                activity.updateGalleryStatus(gallery.getGid(), "下载中");
                // 6. 轮询群晖状态（通过 GID + URL 的 URI 匹配）
                boolean isDownloadComplete = false;
                while (!isDownloadComplete) {
                    // 休眠 5 分钟，Temporal 会将状态挂起，不消耗系统资源
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

            }  catch (ActivityFailure e) {
                Throwable cause = e.getCause();
                if (cause instanceof ApplicationFailure appFailure) {
                    // 获取异常的 Type (即你定义的 Code)
                    String errorType = appFailure.getType();
                    // 根据 Type 进行精准判定
                    if ("QUOTA_EXCEEDED".equals(errorType) || "IP_BANNED".equals(errorType) || "ARCHIVE_LINK_EXTRACT_FAILED".equals(errorType)) {
                        activity.updateGalleryStatus(gallery.getGid(), "阻断");
                        activity.sendEmailAlert("EHentai 抓取阻断", "致命错误 (" + errorType + "): " + appFailure.getOriginalMessage());
                        return; // 遇到致命限制，直接拉闸，终止整个工作流
                    }
                }
                // 如果是普通的非致命异常（在重试 3 次后依然失败），比如超时、暂时连不上群晖
                activity.updateGalleryStatus(gallery.getGid(), "下载失败");
            }
        }
        // 7. 流程结束通知
        activity.sendEmailAlert("抓取流程结束", "本次共处理 " + galleries.size() + " 个画廊");
    }
}
