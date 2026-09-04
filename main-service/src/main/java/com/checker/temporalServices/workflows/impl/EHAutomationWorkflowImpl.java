package com.checker.temporalServices.workflows.impl;

import com.checker.common.Constants;
import com.checker.common.DownloadStatus;
import com.checker.common.GalleryDeduplication;
import com.checker.dto.SearchOptions;
import com.checker.dto.WorkflowSettings;
import com.checker.entity.EhGalleriesEntity;
import com.checker.temporalServices.activities.DatabaseActivity;
import com.checker.temporalServices.activities.NotificationActivity;
import com.checker.temporalServices.activities.ScraperActivity;
import com.checker.temporalServices.workflows.EHAutomationWorkflow;
import com.checker.temporalServices.workflows.SingleGalleryDownloadWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
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

    private final ScraperActivity scraperActivity = Workflow.newActivityStub(
            ScraperActivity.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(Constants.SCRAPER_TASK_QUEUE) // 关键：指定由旁路由节点执行
                    .setStartToCloseTimeout(Duration.ofMinutes(30)) // 爬虫可能较慢，给足超时
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(3)
                            .build())
                    .build()
    );    private final DatabaseActivity databaseActivity = Workflow.newActivityStub(DatabaseActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final NotificationActivity notificationActivity = Workflow.newActivityStub(NotificationActivity.class, WorkflowSteps.DEFAULT_OPTIONS);

    /** 子工作流致命错误标识，用于通知主循环停止派发新任务 */
    private boolean fatalErrorOccurred = false;

    @Override
    public void executeAutomation(SearchOptions searchOptions) {
        int version = Workflow.getVersion("child-workflow-refactor", Workflow.DEFAULT_VERSION, 1);
        int batchNotificationVersion = Workflow.getVersion(
                "batch-email-notification", Workflow.DEFAULT_VERSION, 1);
        int dedupeV2Version = Workflow.getVersion(
                "candidate-score-deduplication", Workflow.DEFAULT_VERSION, 1);
        int dedupeBackfillVersion = Workflow.getVersion(
                "gallery-dedupe-history-backfill", Workflow.DEFAULT_VERSION, 1);
        int visualDedupeVersion = Workflow.getVersion(
                "gallery-visual-deduplication", Workflow.DEFAULT_VERSION, 1);

        // 加载运行时配置（来自 application.yaml，无需重新编译即可调整）
        WorkflowSettings settings = databaseActivity.loadWorkflowSettings();

        // 爬虫抓取画廊列表
        List<EhGalleriesEntity> galleries = deduplicateByGid(scraperActivity.scrapeGalleries(searchOptions));
        if (galleries == null || galleries.isEmpty()) {
            return;
        }

        if (dedupeV2Version != Workflow.DEFAULT_VERSION
                && dedupeBackfillVersion != Workflow.DEFAULT_VERSION) {
            databaseActivity.backfillGalleryDeduplicationMetadata();
        }

        // 批量查询已有记录，并先给同 GID 的历史记录回填本次抓到的作品指纹。
        List<Long> allGids = galleries.stream().map(EhGalleriesEntity::getGid).toList();
        List<EhGalleriesEntity> existingRecords = databaseActivity.getGalleriesByIds(allGids);
        Map<Long, EhGalleriesEntity> existingMap = existingRecords.stream()
                .collect(Collectors.toMap(EhGalleriesEntity::getGid, Function.identity()));

        List<EhGalleriesEntity> knownGalleries = galleries.stream()
                .filter(gallery -> existingMap.containsKey(gallery.getGid()))
                .toList();
        databaseActivity.updateGalleryDeduplicationMetadata(knownGalleries);

        /* 还没下载、需要参与本轮作品级去重的记录 */
        List<EhGalleriesEntity> candidates = new ArrayList<>();
        /* 已下载但未入库 */
        List<EhGalleriesEntity> toCompensate = new ArrayList<>();

        for (EhGalleriesEntity gallery : galleries) {
            EhGalleriesEntity existing = existingMap.get(gallery.getGid());
            if (existing != null) {
                if (hasStatus(existing, DownloadStatus.IMPORTED) ||
                    hasStatus(existing, DownloadStatus.DOWNLOADING) ||
                    hasStatus(existing, DownloadStatus.WAITING_KOMGA) ||
                    hasStatus(existing, DownloadStatus.BLOCKED)) {
                    log.info("⏭️ 画廊已被处理过，状态为: {}，跳过。GID: {}", existing.getDownloadStatus(), gallery.getGid());
                    continue;
                }
                if (hasStatus(existing, DownloadStatus.DOWNLOADED)
                        || hasStatus(existing, DownloadStatus.KOMGA_IMPORT_FAILED)) {
                    log.info("🚀 画廊已下载但未入库，加入补偿队列。GID: {}", gallery.getGid());
                    toCompensate.add(gallery);
                    continue;
                }
            }
            candidates.add(gallery);
        }

        List<EhGalleriesEntity> toDownload = new ArrayList<>();
        List<String> candidateKeys = candidates.stream()
                .filter(GalleryDeduplication::isIdentifiable)
                .map(EhGalleriesEntity::getCandidateKey)
                .distinct()
                .toList();

        if (dedupeV2Version == Workflow.DEFAULT_VERSION) {
            // 旧 Workflow 历史必须保留原来的精确 dedupe_key 决策和命令序列。
            List<String> dedupeKeys = galleries.stream()
                    .filter(EHAutomationWorkflowImpl::hasLegacyDedupeKey)
                    .map(EhGalleriesEntity::getDedupeKey)
                    .toList();
            Map<String, List<EhGalleriesEntity>> persistedPreferredByKey = databaseActivity
                    .findPreferredGalleriesByDedupeKeys(dedupeKeys)
                    .stream()
                    .collect(Collectors.groupingBy(EhGalleriesEntity::getDedupeKey));
            Map<String, List<EhGalleriesEntity>> candidatesByKey = candidates.stream()
                    .filter(EHAutomationWorkflowImpl::hasLegacyDedupeKey)
                    .collect(Collectors.groupingBy(EhGalleriesEntity::getDedupeKey));

            candidates.stream()
                    .filter(gallery -> !hasLegacyDedupeKey(gallery))
                    .forEach(toDownload::add);
            for (Map.Entry<String, List<EhGalleriesEntity>> entry : candidatesByKey.entrySet()) {
                List<EhGalleriesEntity> group = entry.getValue();
                Set<Long> currentGids = group.stream().map(EhGalleriesEntity::getGid).collect(Collectors.toSet());
                List<EhGalleriesEntity> persisted = persistedPreferredByKey
                        .getOrDefault(entry.getKey(), List.of())
                        .stream()
                        .filter(gallery -> !currentGids.contains(gallery.getGid()))
                        .toList();
                if (!persisted.isEmpty()) {
                    EhGalleriesEntity preferred = choosePersistedPreferred(persisted);
                    group.forEach(gallery -> markAsDuplicate(gallery, preferred));
                    continue;
                }
                EhGalleriesEntity preferred = GalleryDeduplication.choosePreferred(group);
                toDownload.add(preferred);
                group.stream()
                        .filter(gallery -> !gallery.getGid().equals(preferred.getGid()))
                        .forEach(gallery -> markAsDuplicate(gallery, preferred));
            }
        } else {
            // V2 先保存全部候选；真正的匹配、动态首选和并发认领在数据库 Activity 内原子完成。
            toDownload.addAll(candidates);
        }

        // 首选版本和被跳过的版本都入库；后者以“已忽略 + duplicate_of_gid”保留供前端查看。
        if (!candidates.isEmpty()) {
            databaseActivity.saveGalleriesBatch(candidates);
        }
        if (visualDedupeVersion != Workflow.DEFAULT_VERSION && !candidates.isEmpty()) {
            List<Long> candidateGids = candidates.stream().map(EhGalleriesEntity::getGid).toList();
            List<EhGalleriesEntity> visualTargets = databaseActivity
                    .findGalleriesNeedingVisualFingerprint(candidateGids);
            if (!visualTargets.isEmpty()) {
                databaseActivity.saveGalleryVisualFingerprints(
                        scraperActivity.analyzeGalleryPreviews(visualTargets));
            }
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
        int startedChildren = 0;

        for (GalleryTask task : tasks) {
            if (fatalErrorOccurred) break;

            // 等待并发窗口有空位
            while (running.size() >= settings.getMaxConcurrency()) {
                Promise.anyOf(running).get();
                running.removeIf(Promise::isCompleted);
            }

            if (dedupeV2Version != Workflow.DEFAULT_VERSION
                    && !task.compensateOnly
                    && !databaseActivity.claimGalleryForDownload(task.gallery.getGid())) {
                log.info("⏭️ 数据库原子去重判定跳过 GID: {}", task.gallery.getGid());
                continue;
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
            startedChildren++;
        }

        // 等待所有剩余子工作流完成
        for (Promise<Void> p : running) {
            try {
                p.get();
            } catch (Exception e) {
                log.error("❌ 等待子工作流完成时发生异常", e);
            }
        }

        if (dedupeV2Version != Workflow.DEFAULT_VERSION && !candidateKeys.isEmpty()) {
            databaseActivity.reconcileGalleryDeduplication(candidateKeys);
        }

        if (batchNotificationVersion == Workflow.DEFAULT_VERSION) {
            // 兼容正在运行的旧 Workflow 历史。
            notificationActivity.sendEmailAlert("抓取流程结束", "本次共处理 " + galleries.size() + " 个画廊");
        } else {
            List<Long> taskGids = tasks.stream().map(task -> task.gallery.getGid()).toList();
            List<EhGalleriesEntity> finalStates = databaseActivity.getGalleriesByIds(taskGids);
            String content = WorkflowSteps.buildBatchNotificationContent(
                    "抓取流程", galleries.size(), tasks.size(), startedChildren,
                    fatalErrorOccurred, finalStates);
            notificationActivity.sendEmailAlert("抓取流程汇总", content);
        }
    }

    private static List<EhGalleriesEntity> deduplicateByGid(List<EhGalleriesEntity> galleries) {
        if (galleries == null || galleries.isEmpty()) {
            return List.of();
        }
        Map<Long, EhGalleriesEntity> unique = new LinkedHashMap<>();
        for (EhGalleriesEntity gallery : galleries) {
            if (gallery != null && gallery.getGid() != null) {
                unique.putIfAbsent(gallery.getGid(), gallery);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static boolean hasStatus(EhGalleriesEntity gallery, DownloadStatus status) {
        return status.getValue().equals(gallery.getDownloadStatus()) || status.name().equals(gallery.getDownloadStatus());
    }

    private static boolean hasLegacyDedupeKey(EhGalleriesEntity gallery) {
        return gallery != null && gallery.getDedupeKey() != null && !gallery.getDedupeKey().isBlank();
    }

    private static EhGalleriesEntity choosePersistedPreferred(List<EhGalleriesEntity> galleries) {
        int bestStatusPriority = galleries.stream()
                .mapToInt(EHAutomationWorkflowImpl::statusPriority)
                .max()
                .orElse(0);
        List<EhGalleriesEntity> equallyUsable = galleries.stream()
                .filter(gallery -> statusPriority(gallery) == bestStatusPriority)
                .toList();
        return GalleryDeduplication.choosePreferred(equallyUsable);
    }

    private static int statusPriority(EhGalleriesEntity gallery) {
        if (hasStatus(gallery, DownloadStatus.IMPORTED)) return 4;
        if (hasStatus(gallery, DownloadStatus.DOWNLOADING)
                || hasStatus(gallery, DownloadStatus.WAITING_KOMGA)) return 3;
        if (hasStatus(gallery, DownloadStatus.DOWNLOADED)
                || hasStatus(gallery, DownloadStatus.KOMGA_IMPORT_FAILED)) return 2;
        if (hasStatus(gallery, DownloadStatus.PENDING)) return 1;
        return 0;
    }

    private static void markAsDuplicate(EhGalleriesEntity gallery, EhGalleriesEntity preferred) {
        gallery.setDuplicateOfGid(preferred.getGid());
        if (gallery.getDedupeConfidence() == null) {
            gallery.setDedupeConfidence(preferred.getDedupeConfidence());
        }
        gallery.setDownloadStatus(DownloadStatus.IGNORED.getValue());
    }
    /** 内部任务包装：标记画廊是否仅需补偿 */
    private record GalleryTask(EhGalleriesEntity gallery, boolean compensateOnly) {}
}
