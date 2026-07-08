package com.checker.temporalServices.workflows.impl;

import com.checker.common.Constants;
import com.checker.common.DownloadStatus;
import com.checker.common.ErrorType;
import com.checker.dto.ArchiveDownloadInfo;
import com.checker.dto.WorkflowSettings;
import com.checker.entity.EhGalleriesEntity;
import com.checker.temporalServices.activities.DatabaseActivity;
import com.checker.temporalServices.activities.KomgaActivity;
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
    private final KomgaActivity komgaActivity = Workflow.newActivityStub(KomgaActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final NotificationActivity notificationActivity = Workflow.newActivityStub(NotificationActivity.class, WorkflowSteps.DEFAULT_OPTIONS);

    @Override
    public void processSingleGallery(EhGalleriesEntity gallery, boolean compensateOnly, WorkflowSettings settings) {
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
                ChildWorkflowOptions childOptions = ChildWorkflowOptions.newBuilder()
                        .setWorkflowId("komga-import-" + gallery.getGid())
                        // 父工作流结束时，不取消子工作流，让它在后台继续活下去
                        .setParentClosePolicy(io.temporal.api.enums.v1.ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                        .build();
                KomgaImportWorkflow komgaWorkflow = Workflow.newChildWorkflowStub(KomgaImportWorkflow.class, childOptions);
                // 2. 异步调用，注意绝对不能加 .get() 阻塞自己
                procedure(() -> komgaWorkflow.waitForImport(
                        gallery,
                        settings.getKomgaImportMaxRetries(),
                        settings.getKomgaImportPollIntervalSeconds(),
                        "⚠️ Komga 入库超时",
                        "未识别或尝试补偿入库失败: " + gallery.getTitle()
                ));
                // 3. 🌟关键点：等待子工作流在 Temporal 服务端“启动”成功
                // 确保后台任务已被服务器接管，随后当前下载工作流就可以放心地立刻 return 结束
                Promise<WorkflowExecution> executionPromise =
                        Workflow.getWorkflowExecution(komgaWorkflow);
                executionPromise.get();
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

                long estimatedWaitSeconds = Math.max((long) ((sizeMb / 3.0) * 1.1), 30);

                Long synologyTaskId = synologyActivity.pushToSynology(downloadUrl, gallery.getGid(), null);
                databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOADING.getValue());

                log.info("预估文件大小 {} MB，休眠 {} 秒后轮询群晖...", sizeMb, estimatedWaitSeconds);
                Workflow.sleep(Duration.ofSeconds(estimatedWaitSeconds));

                while (true) {
                    String status = synologyActivity.checkSynologyTaskStatus(synologyTaskId, downloadUrl);

                    if ("finished".equalsIgnoreCase(status)) {
                        databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOADED.getValue());

                        ChildWorkflowOptions childOptions = ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("komga-import-" + gallery.getGid())
                                // 父工作流结束时，不取消子工作流，让它在后台继续活下去
                                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                                .build();
                        KomgaImportWorkflow komgaWorkflow = Workflow.newChildWorkflowStub(KomgaImportWorkflow.class, childOptions);
                        // 2. 异步调用，注意绝对不能加 .get() 阻塞自己
                        procedure(() -> komgaWorkflow.waitForImport(
                                gallery,
                                settings.getKomgaImportMaxRetries(),
                                settings.getKomgaImportPollIntervalSeconds(),
                                "⚠️ Komga 入库超时",
                                "未识别或尝试补偿入库失败: " + gallery.getTitle()
                        ));
                        // 3. 🌟关键点：等待子工作流在 Temporal 服务端“启动”成功
                        // 确保后台任务已被服务器接管，随后当前下载工作流就可以放心地立刻 return 结束
                        Promise<WorkflowExecution> executionPromise =
                                Workflow.getWorkflowExecution(komgaWorkflow);
                        executionPromise.get();

                        return true; // 成功，退出 retry 块

                    } else if ("error".equalsIgnoreCase(status)) {
                        log.warn("⚠️ 拦截到群晖异常或伪装文件，抛出异常触发 Temporal 重试...");
                        // 抛出 ApplicationFailure 异常。
                        // Temporal 捕获到后，会自动等待 15 秒并重新执行这段 Lambda 代码
                        throw ApplicationFailure.newFailure("Synology returned error or fake file", "SYNOLOGY_DOWNLOAD_ERROR");
                    } else {
                        Workflow.sleep(Duration.ofSeconds(20));
                    }
                }
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
                    notificationActivity.sendEmailAlert("❌ EHentai 抓取阻断", "致命错误: " + appFailure.getOriginalMessage());
                    throw e; // 抛出异常阻断父工作流
                }
                if ("SYNOLOGY_DOWNLOAD_ERROR".equals(errorType)) {
                    log.error("❌ 拦截到伪装小文件，已直接放弃重试，GID: {}", gallery.getGid());
                    databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOAD_FAILED.getValue());
                    return; // 直接 return 结束当前子工作流，释放并发槽位
                }
            }
            // 无论是 3 次 SYNOLOGY_DOWNLOAD_ERROR 还是 Activity 的网络异常耗尽了次数，都在此处兜底
            log.error("❌ 经过最大次数重试后仍然失败，GID: {}", gallery.getGid());
            databaseActivity.updateGalleryStatus(gallery.getGid(), DownloadStatus.DOWNLOAD_FAILED.getValue());
            notificationActivity.sendEmailAlert("❌ 群晖下载异常", "画廊: " + gallery.getTitle() + " 下载连续失败，已达最大重试次数");
        }
    }
}
