package com.checker.temporalServices.workflows.impl;

import com.checker.common.Constants;
import com.checker.common.DownloadStatus;
import com.checker.common.GalleryDeduplication;
import com.checker.dto.SearchOptions;
import com.checker.dto.WorkflowSettings;
import com.checker.dto.GalleryScrapePage;
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
 * 编排画廊采集、分批存库、候选去重和下载入库子流程。
 * 仅在并发窗口有空位时启动下一本，直链由子流程临执行时提取。
 * 版本标记保留旧历史的命令顺序，使已运行流程升级后仍能重放。
 */
@WorkflowImpl(taskQueues = Constants.TASK_QUEUE)
public class EHAutomationWorkflowImpl implements EHAutomationWorkflow {
    private static final Logger log = Workflow.getLogger(EHAutomationWorkflowImpl.class);
    // 给大范围检索留出空间，同时限制异常下一页链接可能造成的持续翻页。
    private static final int MAX_SAFE_SCRAPE_PAGES = 1_000;
    private static final Duration SCRAPE_PAGE_DELAY = Duration.ofSeconds(3);
    /** 以单页量级传递实体，避免活动参数或响应超过任务引擎的载荷限制。 */
    private static final int ENTITY_ACTIVITY_BATCH_SIZE = 25;

    private final ScraperActivity scraperActivity = Workflow.newActivityStub(
            ScraperActivity.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(Constants.SCRAPER_TASK_QUEUE) // 明确派发到注册了抓取活动的工作队列。
                    .setStartToCloseTimeout(Duration.ofMinutes(30)) // 为源站较慢的网络响应预留执行时间。
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(3)
                            .build())
                    .build()
    );    private final DatabaseActivity databaseActivity = Workflow.newActivityStub(DatabaseActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final NotificationActivity notificationActivity = Workflow.newActivityStub(NotificationActivity.class, WorkflowSteps.DEFAULT_OPTIONS);

    /** 子工作流致命错误标识，用于通知主循环停止派发新任务 */
    private boolean fatalErrorOccurred = false;

    @Override
    /** 读取设置和版本标记，分阶段采集、去重、派发子流程，等待已启动任务结束后汇总通知。 */
    public void executeAutomation(SearchOptions searchOptions) {
        // 版本值记录在历史中；重放旧流程时必须继续走原分支，不能改变已经发生的命令顺序。
        int version = Workflow.getVersion("child-workflow-refactor", Workflow.DEFAULT_VERSION, 1);
        int batchNotificationVersion = Workflow.getVersion(
                "batch-email-notification", Workflow.DEFAULT_VERSION, 1);
        int dedupeV2Version = Workflow.getVersion(
                "candidate-score-deduplication", Workflow.DEFAULT_VERSION, 1);
        int dedupeBackfillVersion = Workflow.getVersion(
                "gallery-dedupe-history-backfill", Workflow.DEFAULT_VERSION, 1);
        int visualDedupeVersion = Workflow.getVersion(
                "gallery-visual-deduplication", Workflow.DEFAULT_VERSION, 1);

        // 通过活动读取运行设置；结果写入历史，后续重放仍使用本次任务当时的配置。
        WorkflowSettings settings = databaseActivity.loadWorkflowSettings();

        // 新流程按页接收抓取结果，避免大搜索的 Activity 完成响应超过 Temporal 4 MiB gRPC 上限。
        // 旧历史不能改变命令序列，保留原 Activity 调用以便已执行的工作流可重放。
        int pagedScraperVersion = Workflow.getVersion(
                "paginated-scraper-results", Workflow.DEFAULT_VERSION, 1);
        int gidScraperVersion = Workflow.getVersion(
                "direct-gid-scraper", Workflow.DEFAULT_VERSION, 1);
        boolean hasGalleryUrls = searchOptions.getGalleryUrls() != null && !searchOptions.getGalleryUrls().isEmpty();
        List<EhGalleriesEntity> scraped = gidScraperVersion != Workflow.DEFAULT_VERSION && hasGalleryUrls
                ? scraperActivity.scrapeGalleriesByUrls(searchOptions.getGalleryUrls())
                : (pagedScraperVersion == Workflow.DEFAULT_VERSION
                    ? scraperActivity.scrapeGalleries(searchOptions)
                    : scrapeGalleriesByPage(searchOptions));
        List<EhGalleriesEntity> galleries = deduplicateByGid(scraped);
        if (galleries == null || galleries.isEmpty()) {
            return;
        }

        if (dedupeV2Version != Workflow.DEFAULT_VERSION
                && dedupeBackfillVersion != Workflow.DEFAULT_VERSION) {
            databaseActivity.backfillGalleryDeduplicationMetadata();
        }

        // 批量查询已有记录，并先给同 GID 的历史记录回填本次抓到的作品指纹。
        List<Long> allGids = galleries.stream().map(EhGalleriesEntity::getGid).toList();
        List<EhGalleriesEntity> existingRecords = getGalleriesByIdsInBatches(allGids);
        Map<Long, EhGalleriesEntity> existingMap = existingRecords.stream()
                .collect(Collectors.toMap(EhGalleriesEntity::getGid, Function.identity()));

        List<EhGalleriesEntity> knownGalleries = galleries.stream()
                .filter(gallery -> existingMap.containsKey(gallery.getGid()))
                .toList();
        forEachEntityBatch(knownGalleries, databaseActivity::updateGalleryDeduplicationMetadata);

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

        // 先保存所有候选，再由对应版本的判断流程确定首选和重复关系，避免漏掉被跳过的版本。
        if (!candidates.isEmpty()) {
            forEachEntityBatch(candidates, databaseActivity::saveGalleriesBatch);
        }
        if (visualDedupeVersion != Workflow.DEFAULT_VERSION && !candidates.isEmpty()) {
            List<Long> candidateGids = candidates.stream().map(EhGalleriesEntity::getGid).toList();
            for (List<Long> gidBatch : batches(candidateGids, ENTITY_ACTIVITY_BATCH_SIZE)) {
                List<EhGalleriesEntity> visualTargets = databaseActivity
                        .findGalleriesNeedingVisualFingerprint(gidBatch);
                forEachEntityBatch(visualTargets, targets -> databaseActivity.saveGalleryVisualFingerprints(
                        scraperActivity.analyzeGalleryPreviews(targets)));
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

        // 用固定容量的运行窗口派发子流程，任一本结束后才补入下一本。
        List<Promise<Void>> running = new ArrayList<>();
        int startedChildren = 0;

        for (GalleryTask task : tasks) {
            if (fatalErrorOccurred) break;

            // 等到至少一个子流程完成后移除已完成项，保持同时下载数量在设置范围内。
            while (running.size() >= settings.getMaxConcurrency()) {
                Promise.anyOf(running).get();
                running.removeIf(Promise::isCompleted);
            }

            // 真正启动下载前再次原子认领，防止另一个父流程已经派发同作品版本。
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
            List<EhGalleriesEntity> finalStates = getGalleriesByIdsInBatches(taskGids);
            String content = WorkflowSteps.buildBatchNotificationContent(
                    "抓取流程", galleries.size(), tasks.size(), startedChildren,
                    fatalErrorOccurred, finalStates);
            notificationActivity.sendEmailAlert("抓取流程汇总", content);
        }
    }

    /** 保留首次出现的有效 GID，避免分页重复链接进入后续下载计划。 */
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

    /** 逐页请求抓取活动并记录停止游标，每页之间等待，达到安全上限时明确结束。 */
    private List<EhGalleriesEntity> scrapeGalleriesByPage(SearchOptions searchOptions) {
        List<EhGalleriesEntity> galleries = new ArrayList<>();
        String currentUrl = null;
        String finalCursor = null;
        for (int pageNo = 1; pageNo <= MAX_SAFE_SCRAPE_PAGES; pageNo++) {
            GalleryScrapePage page = scraperActivity.scrapeGalleryPage(searchOptions, currentUrl, pageNo);
            if (page.getGalleries() != null) galleries.addAll(page.getGalleries());
            finalCursor = page.getNextUrl();
            if (!page.isHasData() || !page.isHasNextPage()) {
                String stopReason = page.getStopReason() == null ? "end_of_pages" : page.getStopReason();
                String cursor = finalCursor;
                galleries.forEach(gallery -> {
                    gallery.setTraceStopReason(stopReason);
                    gallery.setTraceLastNextCursor(cursor);
                });
                return galleries;
            }
            currentUrl = page.getNextUrl();
            Workflow.sleep(SCRAPE_PAGE_DELAY);
        }
        String cursor = finalCursor;
        galleries.forEach(gallery -> {
            gallery.setTraceStopReason("max_safe_pages_reached");
            gallery.setTraceLastNextCursor(cursor);
        });
        log.warn("达到最大安全翻页限制 {}，停止抓取，防止死循环。", MAX_SAFE_SCRAPE_PAGES);
        return galleries;
    }

    /**
     * 活动参数与响应都会写入任务历史，因此实体列表按固定小批传递。
     * 每批可以独立重试，也避免大范围检索一次序列化全部画廊而超过载荷限制。
     */
    private void forEachEntityBatch(List<EhGalleriesEntity> galleries,
                                    java.util.function.Consumer<List<EhGalleriesEntity>> operation) {
        for (List<EhGalleriesEntity> batch : batches(galleries, ENTITY_ACTIVITY_BATCH_SIZE)) {
            operation.accept(batch);
        }
    }

    /** 分批读取最新数据库状态，避免活动响应携带过大的实体列表。 */
    private List<EhGalleriesEntity> getGalleriesByIdsInBatches(List<Long> gids) {
        List<EhGalleriesEntity> results = new ArrayList<>();
        for (List<Long> batch : batches(gids, ENTITY_ACTIVITY_BATCH_SIZE)) {
            results.addAll(databaseActivity.getGalleriesByIds(batch));
        }
        return results;
    }

    /** 按固定条数复制小批次，供活动调用逐批序列化和重试。 */
    private static <T> List<List<T>> batches(List<T> values, int size) {
        if (values == null || values.isEmpty()) return List.of();
        List<List<T>> batches = new ArrayList<>();
        for (int offset = 0; offset < values.size(); offset += size) {
            batches.add(new ArrayList<>(values.subList(offset, Math.min(offset + size, values.size()))));
        }
        return batches;
    }

    private static boolean hasStatus(EhGalleriesEntity gallery, DownloadStatus status) {
        return status.getValue().equals(gallery.getDownloadStatus()) || status.name().equals(gallery.getDownloadStatus());
    }

    private static boolean hasLegacyDedupeKey(EhGalleriesEntity gallery) {
        return gallery != null && gallery.getDedupeKey() != null && !gallery.getDedupeKey().isBlank();
    }

    /** 旧版分支先按已有记录的可用状态选池，再比较版本质量。 */
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

    /** 给旧版已入库、处理中和待下载记录排序，避免用失败版本替代可用版本。 */
    private static int statusPriority(EhGalleriesEntity gallery) {
        if (hasStatus(gallery, DownloadStatus.IMPORTED)) return 4;
        if (hasStatus(gallery, DownloadStatus.DOWNLOADING)
                || hasStatus(gallery, DownloadStatus.WAITING_KOMGA)) return 3;
        if (hasStatus(gallery, DownloadStatus.DOWNLOADED)
                || hasStatus(gallery, DownloadStatus.KOMGA_IMPORT_FAILED)) return 2;
        if (hasStatus(gallery, DownloadStatus.PENDING)) return 1;
        return 0;
    }

    /** 为旧版去重分支标记重复版本关系，并保留记录供页面展示。 */
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
