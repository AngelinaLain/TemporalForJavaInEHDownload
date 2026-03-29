package com.checker.temporalServices.workflows.impl;

import cn.hutool.core.util.StrUtil;
import com.checker.dto.SearchOptions;
import com.checker.entity.EhGalleriesEntity;
import com.checker.temporalServices.activities.EHAutomationActivity;
import com.checker.temporalServices.workflows.EHAutomationWorkflow;
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

// 绑定到 application.yaml 中配置的 Task Queue
@WorkflowImpl(taskQueues = "EHDownloadTaskQueue")
public class EHAutomationWorkflowImpl implements EHAutomationWorkflow {
    private static final Logger log = Workflow.getLogger(EHAutomationWorkflowImpl.class);
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
        List<Promise<Void>> komgaPromises = new ArrayList<>();
        for (EhGalleriesEntity gallery : galleries) {
            // 先去数据库查一下这个 GID 之前的状态
            EhGalleriesEntity existingGallery = activity.getGalleryById(gallery.getGid());
            if (existingGallery != null) {
                String oldStatus = existingGallery.getDownloadStatus();
                // ⚠️ 注意这里加上了 "已入库"！
                if ("已入库".equals(oldStatus) || "下载中".equals(oldStatus) || "阻断".equals(oldStatus)) {
                    log.info("⏭️ 画廊已被处理过，状态为: {}，跳过。GID: {}", oldStatus, gallery.getGid());
                    continue;
                }

                // 🛠️ 阶段 2：断点补偿机制（群晖下完了，但 Komga 没识别/没打上标签）
                if ("已下载".equals(oldStatus)) {
                    log.info("🚀 画廊已下载但未入库，触发 Komga 补偿机制。GID: {}", gallery.getGid());
                    try {
                        activity.fetchAndSaveMetadata(gallery.getGid(), gallery.getToken());
                        EhGalleriesEntity downloadedGallery = activity.getGalleryById(gallery.getGid());
                        if (downloadedGallery != null && StrUtil.isNotBlank(downloadedGallery.getFilename())) {
                            activity.renameSynologyFile(gallery.getGid(), downloadedGallery.getFilename());
                        }
                        activity.triggerKomgaLibraryScan();
                        // 将 Komga 监控任务丢入异步 Promise
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
                    } catch (Exception e) { // 👈 捕获异常，防止波及其他画廊
                        log.error("补偿机制发生异常 GID: " + gallery.getGid(), e);
                    }
                    // 补偿任务已发出，跳过下方重新拉取直链和推群晖的逻辑！
                    continue;
                }
            }

            // 3. 存入数据库 (只更新或插入那些需要下载的)
            activity.saveToDatabase(gallery);
            try {
                // 4. 获取下载直链
                String downloadUrl = activity.extractDownloadUrl(gallery.getGid(), gallery.getToken());
                // 5. 推送给群晖，返回 GID（用于后续任务追踪）
                Long gid = activity.pushToSynology(downloadUrl, gallery.getGid(), null);
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
                        /* Komga 入库与元数据处理流程 */
                        // 下载完成后，向 EHentai 请求标签数据并存入数据库
                        activity.fetchAndSaveMetadata(gallery.getGid(), gallery.getToken());
                        // 🚀 2. 核心新增：调用物理重命名！
                        EhGalleriesEntity downloadedGallery = activity.getGalleryById(gallery.getGid());
                        if (downloadedGallery != null && StrUtil.isNotBlank(downloadedGallery.getFilename())) {
                            activity.renameSynologyFile(gallery.getGid(), downloadedGallery.getFilename());
                        }
                        // Go Work!!! 踹一脚 Komga，让它立刻干活！
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
                            }
                        });
                        komgaPromises.add(komgaTask);
                    } else if ("error".equalsIgnoreCase(status)) {
                        activity.updateGalleryStatus(gallery.getGid(), "下载失败");
                        activity.sendEmailAlert("❌ 群晖下载异常", "画廊: " + gallery.getTitle() + " 下载失败");
                        break;
                    }
                }
                Workflow.sleep(Duration.ofSeconds(10));
            }  catch (ActivityFailure e) {
                Throwable cause = e.getCause();
                if (cause instanceof ApplicationFailure appFailure) {
                    // 获取异常的 Type (即你定义的 Code)
                    String errorType = appFailure.getType();
                    // 根据 Type 进行精准判定
                    if ("QUOTA_EXCEEDED".equals(errorType) || "IP_BANNED".equals(errorType) || "ARCHIVE_LINK_EXTRACT_FAILED".equals(errorType)) {
                        activity.updateGalleryStatus(gallery.getGid(), "阻断");
                        activity.sendEmailAlert("❌ EHentai 抓取阻断", "致命错误 (" + errorType + "): " + appFailure.getOriginalMessage());
                        return; // 遇到致命限制，直接拉闸，终止整个工作流
                    }
                }
                // 如果是普通的非致命异常（在重试 3 次后依然失败），比如超时、暂时连不上群晖
                activity.updateGalleryStatus(gallery.getGid(), "下载失败");
            }
        }
        if (!komgaPromises.isEmpty()) {
            Promise.allOf(komgaPromises).get();
        }
        // 7. 流程结束通知
        activity.sendEmailAlert("抓取流程结束", "本次共处理 " + galleries.size() + " 个画廊");
    }
}
