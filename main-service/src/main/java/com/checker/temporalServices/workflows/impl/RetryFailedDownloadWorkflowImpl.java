package com.checker.temporalServices.workflows.impl;

import com.checker.common.Constants;
import com.checker.common.DownloadStatus;
import com.checker.dto.WorkflowSettings;
import com.checker.entity.EhGalleriesEntity;
import com.checker.temporalServices.activities.DatabaseActivity;
import com.checker.temporalServices.activities.NotificationActivity;
import com.checker.temporalServices.workflows.RetryFailedDownloadWorkflow;
import com.checker.temporalServices.workflows.SingleGalleryDownloadWorkflow;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 重试失败下载工作流实现：查询失败画廊并通过子工作流重新执行下载、入库流程
 * <p>
 * 与主工作流共享同一套子工作流和滑动窗口并发控制机制。
 */
@WorkflowImpl(taskQueues = Constants.TASK_QUEUE)
public class RetryFailedDownloadWorkflowImpl implements RetryFailedDownloadWorkflow {
    private static final Logger log = Workflow.getLogger(RetryFailedDownloadWorkflowImpl.class);

    // 数据库的活动设置进来
    private final DatabaseActivity databaseActivity = Workflow.newActivityStub(DatabaseActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    // 将邮件的发送设置进来
    private final NotificationActivity notificationActivity = Workflow.newActivityStub(NotificationActivity.class, WorkflowSteps.DEFAULT_OPTIONS);

    /** 子工作流致命错误标识 */
    private boolean fatalErrorOccurred = false;

    @Override
    public void retryFailedTasks() {
        int version = Workflow.getVersion("child-workflow-refactor", Workflow.DEFAULT_VERSION, 1);
        int batchNotificationVersion = Workflow.getVersion(
                "batch-email-notification", Workflow.DEFAULT_VERSION, 1);
        int dedupeV2Version = Workflow.getVersion(
                "candidate-score-deduplication", Workflow.DEFAULT_VERSION, 1);
        int dedupeBackfillVersion = Workflow.getVersion(
                "gallery-dedupe-history-backfill", Workflow.DEFAULT_VERSION, 1);

        if (dedupeV2Version != Workflow.DEFAULT_VERSION
                && dedupeBackfillVersion != Workflow.DEFAULT_VERSION) {
            databaseActivity.backfillGalleryDeduplicationMetadata();
        }

        // 加载运行时配置
        WorkflowSettings settings = databaseActivity.loadWorkflowSettings();


        List<EhGalleriesEntity> failedGalleries = databaseActivity.getFailedGalleries();
        if (failedGalleries == null || failedGalleries.isEmpty()) {
            return;
        }

        // 滑动窗口并发控制：派发子工作流
        List<Promise<Void>> running = new ArrayList<>();
        int startedChildren = 0;

        for (EhGalleriesEntity gallery : failedGalleries) {
            if (fatalErrorOccurred) break;

            while (running.size() >= settings.getMaxConcurrency()) {
                Promise.anyOf(running).get();
                running.removeIf(Promise::isCompleted);
            }

            boolean compensateOnly = DownloadStatus.DOWNLOADED.getValue().equals(gallery.getDownloadStatus())
                    || DownloadStatus.WAITING_KOMGA.getValue().equals(gallery.getDownloadStatus())
                    || DownloadStatus.KOMGA_IMPORT_FAILED.getValue().equals(gallery.getDownloadStatus());
            if (dedupeV2Version != Workflow.DEFAULT_VERSION
                    && !compensateOnly
                    && !databaseActivity.claimGalleryForDownload(gallery.getGid())) {
                log.info("数据库原子去重判定跳过重试 GID: {}", gallery.getGid());
                continue;
            }

            ChildWorkflowOptions childOptions = ChildWorkflowOptions.newBuilder()
                    .setWorkflowId("retry-gallery-" + gallery.getGid())
                    .setTaskQueue(Constants.TASK_QUEUE)
                    .build();
            SingleGalleryDownloadWorkflow child = Workflow.newChildWorkflowStub(
                    SingleGalleryDownloadWorkflow.class, childOptions);

            Promise<Void> promise = Async.procedure(() -> {
                try {
                    child.processSingleGallery(gallery, compensateOnly, settings);
                } catch (ChildWorkflowFailure e) {
                    log.error("子工作流异常终止（致命错误），停止派发新任务。GID: {}", gallery.getGid());
                    fatalErrorOccurred = true;
                }
            });
            running.add(promise);
            startedChildren++;
        }

        // 等待所有剩余子工作流完成
        for (Promise<Void> p : running) {
            try {
                p.get();
            } catch (Exception e) {
                log.error("等待子工作流完成时发生异常", e);
            }
        }

        if (dedupeV2Version != Workflow.DEFAULT_VERSION) {
            List<String> candidateKeys = failedGalleries.stream()
                    .map(EhGalleriesEntity::getCandidateKey)
                    .filter(key -> key != null && !key.isBlank())
                    .distinct()
                    .toList();
            databaseActivity.reconcileGalleryDeduplication(candidateKeys);
        }

        if (batchNotificationVersion == Workflow.DEFAULT_VERSION) {
            // 兼容正在运行的旧 Workflow 历史。
            notificationActivity.sendEmailAlert("重试流程结束", "本次共重试了 " + failedGalleries.size() + " 个画廊");
        } else {
            List<Long> gids = failedGalleries.stream().map(EhGalleriesEntity::getGid).toList();
            List<EhGalleriesEntity> finalStates = databaseActivity.getGalleriesByIds(gids);
            String content = WorkflowSteps.buildBatchNotificationContent(
                    "重试流程", failedGalleries.size(), failedGalleries.size(), startedChildren,
                    fatalErrorOccurred, finalStates);
            notificationActivity.sendEmailAlert("重试流程汇总", content);
        }
    }
}
