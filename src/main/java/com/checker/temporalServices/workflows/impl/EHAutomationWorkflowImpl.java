package com.checker.temporalServices.workflows.impl;

import com.checker.common.Constants;
import com.checker.common.DownloadStatus;
import com.checker.dto.SearchOptions;
import com.checker.dto.WorkflowSettings;
import com.checker.entity.EhGalleriesEntity;
import com.checker.temporalServices.activities.DatabaseActivity;
import com.checker.temporalServices.activities.NotificationActivity;
import com.checker.temporalServices.activities.ScraperActivity;
import com.checker.temporalServices.workflows.EHAutomationWorkflow;
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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * EHentai 自动化主工作流实现：编排爬虫→入库→下载→Komga导入全流程
 * <p>
 * 核心架构优化：
 * <ul>
 *   <li>子工作流 (Child Workflow)：每本画廊的下载/轮询逻辑隔离到独立子工作流，
 *       彻底解决主工作流历史记录爆炸（50,000 条限制）问题</li>
 *   <li>滑动窗口并发控制：最大并发度可配置（默认 2），既最大化带宽利用率，
 *       又避免触发 EHentai 并发限制</li>
 *   <li>惰性提取 (Lazy Extraction)：子工作流执行时才获取直链，避免排队过久链接过期</li>
 *   <li>批量数据库操作：一次性查询/保存，替代逐条 Activity 调用</li>
 * </ul>
 */
@WorkflowImpl(taskQueues = Constants.TASK_QUEUE)
public class EHAutomationWorkflowImpl implements EHAutomationWorkflow {
    private static final Logger log = Workflow.getLogger(EHAutomationWorkflowImpl.class);

    private final ScraperActivity scraperActivity = Workflow.newActivityStub(ScraperActivity.class, WorkflowSteps.SCRAPER_OPTIONS);
    private final DatabaseActivity databaseActivity = Workflow.newActivityStub(DatabaseActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final NotificationActivity notificationActivity = Workflow.newActivityStub(NotificationActivity.class, WorkflowSteps.DEFAULT_OPTIONS);

    /** 子工作流致命错误标识，用于通知主循环停止派发新任务 */
    private boolean fatalErrorOccurred = false;

    @Override
    public void executeAutomation(SearchOptions searchOptions) {
        int version = Workflow.getVersion("child-workflow-refactor", Workflow.DEFAULT_VERSION, 1);

        // 加载运行时配置（来自 application.yaml，无需重新编译即可调整）
        WorkflowSettings settings = databaseActivity.loadWorkflowSettings();

        // 爬虫抓取画廊列表
        List<EhGalleriesEntity> galleries = scraperActivity.scrapeGalleries(searchOptions);
        if (galleries == null || galleries.isEmpty()) {
            return;
        }

        //  批量查询已有记录，按状态分类
        List<Long> allGids = galleries.stream().map(EhGalleriesEntity::getGid).toList();
        List<EhGalleriesEntity> existingRecords = databaseActivity.getGalleriesByIds(allGids);
        Map<Long, EhGalleriesEntity> existingMap = existingRecords.stream()
                .collect(Collectors.toMap(EhGalleriesEntity::getGid, Function.identity()));

        /* 还没下载的 */
        List<EhGalleriesEntity> toDownload = new ArrayList<>();
        /* 已下载但未入库 */
        List<EhGalleriesEntity> toCompensate = new ArrayList<>();

        for (EhGalleriesEntity gallery : galleries) {
            EhGalleriesEntity existing = existingMap.get(gallery.getGid());
            if (existing != null) {
                String oldStatus = existing.getDownloadStatus();
                if (DownloadStatus.IMPORTED.getValue().equals(oldStatus) ||
                    DownloadStatus.DOWNLOADING.getValue().equals(oldStatus) ||
                    DownloadStatus.BLOCKED.getValue().equals(oldStatus)) {
                    log.info("⏭️ 画廊已被处理过，状态为: {}，跳过。GID: {}", oldStatus, gallery.getGid());
                    continue;
                }
                if (DownloadStatus.DOWNLOADED.getValue().equals(oldStatus)) {
                    log.info("🚀 画廊已下载但未入库，加入补偿队列。GID: {}", gallery.getGid());
                    toCompensate.add(gallery);
                    continue;
                }
            }
            toDownload.add(gallery);
        }

        // 批量保存新画廊到数据库
        if (!toDownload.isEmpty()) {
            databaseActivity.saveGalleriesBatch(toDownload);
        }

        // 合并任务列表：补偿任务优先（已下载，只需入库）
        List<GalleryTask> tasks = new ArrayList<>();
        for (EhGalleriesEntity g : toCompensate) {
            tasks.add(new GalleryTask(g, true));
        }
        for (EhGalleriesEntity g : toDownload) {
            tasks.add(new GalleryTask(g, false));
        }

        // 滑动窗口并发控制：派发子工作流
        List<Promise<Void>> running = new ArrayList<>();

        for (GalleryTask task : tasks) {
            if (fatalErrorOccurred) break;

            // 等待并发窗口有空位
            while (running.size() >= settings.getMaxConcurrency()) {
                Promise.anyOf(running).get();
                running.removeIf(Promise::isCompleted);
            }

            ChildWorkflowOptions childOptions = ChildWorkflowOptions.newBuilder()
                    .setWorkflowId("single-gallery-" + task.gallery.getGid())
                    .setTaskQueue(Constants.TASK_QUEUE)
                    .build();
            SingleGalleryDownloadWorkflow child = Workflow.newChildWorkflowStub(
                    SingleGalleryDownloadWorkflow.class, childOptions);

            Promise<Void> promise = Async.procedure(() -> {
                try {
                    child.processSingleGallery(task.gallery, task.compensateOnly, settings);
                } catch (ChildWorkflowFailure e) {
                    log.error("❌ 子工作流异常终止（致命错误），停止派发新任务。GID: {}", task.gallery.getGid());
                    fatalErrorOccurred = true;
                }
            });
            running.add(promise);
        }

        // 等待所有剩余子工作流完成
        for (Promise<Void> p : running) {
            try {
                p.get();
            } catch (Exception e) {
                log.error("❌ 等待子工作流完成时发生异常", e);
            }
        }

        notificationActivity.sendEmailAlert("抓取流程结束", "本次共处理 " + galleries.size() + " 个画廊");
    }

    /** 内部任务包装：标记画廊是否仅需补偿 */
    private record GalleryTask(EhGalleriesEntity gallery, boolean compensateOnly) {}
}
