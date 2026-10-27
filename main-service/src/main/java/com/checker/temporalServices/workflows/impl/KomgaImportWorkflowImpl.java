package com.checker.temporalServices.workflows.impl;

import cn.hutool.core.util.StrUtil;
import com.checker.common.Constants;
import com.checker.common.DownloadStatus;
import com.checker.dto.KomgaBookMatchResult;
import com.checker.entity.EhGalleriesEntity;
import com.checker.temporalServices.activities.AiActivity;
import com.checker.temporalServices.activities.DatabaseActivity;
import com.checker.temporalServices.activities.KomgaActivity;
import com.checker.temporalServices.activities.NotificationActivity;
import com.checker.temporalServices.activities.SynologyActivity;
import com.checker.temporalServices.workflows.KomgaImportWorkflow;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;

/**
 * 等待 Komga 扫描并按物理文件唯一确认入库，记录查询失败、歧义或等待超时的原因。
 * 旧工作流沿用历史分支，新工作流按当前设置轮询并交由父流程汇总通知。
 */
@WorkflowImpl(taskQueues = Constants.TASK_QUEUE)
public class KomgaImportWorkflowImpl implements KomgaImportWorkflow {
    private static final Logger log = Workflow.getLogger(KomgaImportWorkflowImpl.class);

    private final KomgaActivity komgaActivity = Workflow.newActivityStub(KomgaActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final NotificationActivity notificationActivity = Workflow.newActivityStub(NotificationActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final DatabaseActivity databaseActivity = Workflow.newActivityStub(DatabaseActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final SynologyActivity synologyActivity = Workflow.newActivityStub(SynologyActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    // AI 调用使用专用推理队列，主要为旧工作流历史保留原调用次序。
    private final AiActivity aiActivity = Workflow.newActivityStub(AiActivity.class, WorkflowSteps.AI_OPTIONS);

    @Override
    /** 准备文件与扫描后按配置轮询，唯一命中才写入库状态；歧义、访问失败和超时分别留痕。 */
    public void waitForImport(EhGalleriesEntity gallery, int maxRetries, int pollIntervalSeconds, String timeoutSubject, String timeoutContent) {
        // 重放时按历史中的版本进入原分支，避免升级后新增或删除已经记录的活动调用。
        int batchNotificationVersion = Workflow.getVersion(
                "batch-email-notification", Workflow.DEFAULT_VERSION, 1);
        int exactConfirmationVersion = Workflow.getVersion(
                "komga-exact-import-confirmation", Workflow.DEFAULT_VERSION, 1);
        int skipAiOnKomgaRetryVersion = Workflow.getVersion(
                "komga-retry-skip-ai-enrichment", Workflow.DEFAULT_VERSION, 1);

        // 先补齐元数据和归档准备步骤，再触发图库扫描；新流程不在此重复生成 AI 简介。
        try {
            // 拉取 EHentai 标签并存库
            komgaActivity.fetchAndSaveMetadata(gallery.getGid(), gallery.getToken());

            if (skipAiOnKomgaRetryVersion == Workflow.DEFAULT_VERSION) {
                // 仅为已经运行的 Temporal 历史保留原调用顺序。新流程的 AI/ComicInfo
                // 在本地下载阶段完成；Komga 补偿只负责尽快确认既有物理文件是否入库。
                try {
                    EhGalleriesEntity withTags = databaseActivity.getGalleryById(gallery.getGid());
                    if (withTags != null && withTags.getTags() != null && !withTags.getTags().isEmpty()) {
                        String summary = aiActivity.generateGallerySummary(gallery.getTitle(), withTags.getTags());
                        if (summary != null && !summary.isBlank()) {
                            databaseActivity.updateGallerySummary(gallery.getGid(), summary);
                            log.info("🤖 AI 概述生成成功, GID: {}", gallery.getGid());
                        }
                    }
                } catch (Exception aiEx) {
                    log.warn("⚠️ AI 概述生成失败，跳过不阻塞入库, GID: {}, 原因: {}",
                            gallery.getGid(), aiEx.getMessage());
                }
            } else {
                log.info("⏭️ Komga 入库确认跳过 AI 概述，直接扫描物理文件, GID: {}", gallery.getGid());
            }

            // 重命名群晖文件
            EhGalleriesEntity downloaded = databaseActivity.getGalleryById(gallery.getGid());
            if (downloaded != null && StrUtil.isNotBlank(downloaded.getFilename())) {
                synologyActivity.renameSynologyFile(gallery.getGid(), downloaded.getFilename());
            }

            if (exactConfirmationVersion == Workflow.DEFAULT_VERSION) {
                // 旧历史保留原来的异常处理和活动顺序，避免重放出现命令不一致。
                komgaActivity.triggerKomgaLibraryScan();
            }
        } catch (Exception e) {
            log.error("执行 Komga 入库前置动作(改名/触发扫描)失败，GID: {}", gallery.getGid(), e);
        }

        if (exactConfirmationVersion != Workflow.DEFAULT_VERSION) {
            databaseActivity.updateGalleryStatus(
                    gallery.getGid(), DownloadStatus.WAITING_KOMGA.getValue());
            try {
                komgaActivity.triggerKomgaLibraryScan();
            } catch (RuntimeException scanFailure) {
                log.error("[❌ Komga 扫描触发失败] GID: {}", gallery.getGid(), scanFailure);
                databaseActivity.recordKomgaConfirmation(gallery.getGid(),
                        DownloadStatus.KOMGA_IMPORT_FAILED.getValue(),
                        "Komga 扫描触发失败: " + scanFailure.getMessage(), null);
                return;
            }
        }

        // 按传入设置等待扫描结果，不使用固定次数；只有唯一物理文件匹配才确认入库。
        boolean isImportedToKomga = false;
        int currentTry = 0;

        while (!isImportedToKomga && currentTry < maxRetries) {
            Workflow.sleep(Duration.ofSeconds(Math.max(1, pollIntervalSeconds)));
            currentTry++;
            if (exactConfirmationVersion == Workflow.DEFAULT_VERSION) {
                String bookId = komgaActivity.findBookInKomga(gallery.getGid());
                if (bookId != null) {
                    komgaActivity.pushMetadataToKomga(bookId, gallery.getGid());
                    isImportedToKomga = true;
                }
                continue;
            }

            KomgaBookMatchResult match;
            try {
                match = komgaActivity.findExactBookInKomga(gallery.getGid());
            } catch (RuntimeException queryFailure) {
                log.error("[❌ Komga 入库确认查询失败] GID: {}", gallery.getGid(), queryFailure);
                databaseActivity.recordKomgaConfirmation(gallery.getGid(),
                        DownloadStatus.KOMGA_IMPORT_FAILED.getValue(),
                        "Komga 入库确认查询失败: " + queryFailure.getMessage(), null);
                return;
            }
            if (match == null) {
                log.error("[❌ Komga 入库确认返回空结果] GID: {}", gallery.getGid());
                databaseActivity.recordKomgaConfirmation(gallery.getGid(),
                        DownloadStatus.KOMGA_IMPORT_FAILED.getValue(),
                        "Komga 入库确认返回空结果", null);
                return;
            }
            String candidateBookIds = String.join(",", match.getCandidateBookIds() == null
                    ? java.util.List.of() : match.getCandidateBookIds());
            if (KomgaBookMatchResult.FOUND.equals(match.getStatus())) {
                try {
                    komgaActivity.pushMetadataToKomga(match.getBookId(), gallery.getGid());
                    databaseActivity.recordKomgaConfirmation(gallery.getGid(),
                            DownloadStatus.IMPORTED.getValue(), match.getReason(), candidateBookIds);
                    isImportedToKomga = true;
                } catch (RuntimeException metadataFailure) {
                    log.error("[❌ Komga 元数据更新失败] GID: {}, BookID: {}",
                            gallery.getGid(), match.getBookId(), metadataFailure);
                    databaseActivity.recordKomgaConfirmation(gallery.getGid(),
                            DownloadStatus.KOMGA_IMPORT_FAILED.getValue(),
                            "Komga 元数据更新失败: " + metadataFailure.getMessage(), candidateBookIds);
                    return;
                }
            } else if (KomgaBookMatchResult.AMBIGUOUS.equals(match.getStatus())) {
                // 多个候选不能随意选第一本，停止本轮确认并保留候选供人工核对。
                log.error("[❌ Komga 匹配冲突] GID: {}, 原因: {}", gallery.getGid(), match.getReason());
                databaseActivity.recordKomgaConfirmation(gallery.getGid(),
                        DownloadStatus.KOMGA_IMPORT_FAILED.getValue(), match.getReason(), candidateBookIds);
                return;
            } else {
                databaseActivity.recordKomgaConfirmation(gallery.getGid(),
                        DownloadStatus.WAITING_KOMGA.getValue(), match.getReason(), candidateBookIds);
                int safeInterval = Math.max(1, pollIntervalSeconds);
                int rescanAttempt = Math.max(1, 120 / safeInterval);
                // 等待一段时间仍未识别时再触发一次扫描，避免每次轮询都重复扫描图库。
                if (currentTry == rescanAttempt && currentTry < maxRetries) {
                    try {
                        komgaActivity.triggerKomgaLibraryScan();
                    } catch (RuntimeException rescanFailure) {
                        log.error("[❌ Komga 二次扫描触发失败] GID: {}", gallery.getGid(), rescanFailure);
                        databaseActivity.recordKomgaConfirmation(gallery.getGid(),
                                DownloadStatus.KOMGA_IMPORT_FAILED.getValue(),
                                "Komga 二次扫描触发失败: " + rescanFailure.getMessage(), candidateBookIds);
                        return;
                    }
                }
            }
        }

        if (!isImportedToKomga) {
            log.warn("[⚠️ Komga 入库超时] GID: {}, 标题: {}", gallery.getGid(), gallery.getTitle());
            if (exactConfirmationVersion != Workflow.DEFAULT_VERSION) {
                databaseActivity.recordKomgaConfirmation(gallery.getGid(),
                        DownloadStatus.KOMGA_IMPORT_FAILED.getValue(),
                        "等待 Komga 入库确认超时", null);
            }
            if (batchNotificationVersion == Workflow.DEFAULT_VERSION) {
                // 仅兼容旧 Workflow 历史；新流程由最外层父工作流统一汇总发信。
                notificationActivity.sendEmailAlert(timeoutSubject, timeoutContent);
            }
        }
    }
}
