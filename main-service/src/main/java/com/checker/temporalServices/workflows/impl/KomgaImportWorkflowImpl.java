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

@WorkflowImpl(taskQueues = Constants.TASK_QUEUE)
public class KomgaImportWorkflowImpl implements KomgaImportWorkflow {
    private static final Logger log = Workflow.getLogger(KomgaImportWorkflowImpl.class);

    private final KomgaActivity komgaActivity = Workflow.newActivityStub(KomgaActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final NotificationActivity notificationActivity = Workflow.newActivityStub(NotificationActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final DatabaseActivity databaseActivity = Workflow.newActivityStub(DatabaseActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    private final SynologyActivity synologyActivity = Workflow.newActivityStub(SynologyActivity.class, WorkflowSteps.DEFAULT_OPTIONS);
    // AI_OPTIONS 显式指定 EH_TASK_QUEUE，与 AiActivityImpl 注册的队列保持一致
    private final AiActivity aiActivity = Workflow.newActivityStub(AiActivity.class, WorkflowSteps.AI_OPTIONS);

    @Override
    public void waitForImport(EhGalleriesEntity gallery, int maxRetries, int pollIntervalSeconds, String timeoutSubject, String timeoutContent) {
        int batchNotificationVersion = Workflow.getVersion(
                "batch-email-notification", Workflow.DEFAULT_VERSION, 1);
        int exactConfirmationVersion = Workflow.getVersion(
                "komga-exact-import-confirmation", Workflow.DEFAULT_VERSION, 1);

        // ==========================================
        // 1. 核心修复：执行原来的 postDownloadKomgaProcess 逻辑
        // ==========================================
        try {
            // 拉取 EHentai 标签并存库
            komgaActivity.fetchAndSaveMetadata(gallery.getGid(), gallery.getToken());

            // AI 生成内容概述（失败不阻塞后续入库流程）
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
                log.warn("⚠️ AI 概述生成失败，跳过不阻塞入库, GID: {}, 原因: {}", gallery.getGid(), aiEx.getMessage());
            }

            // 重命名群晖文件
            EhGalleriesEntity downloaded = databaseActivity.getGalleryById(gallery.getGid());
            if (downloaded != null && StrUtil.isNotBlank(downloaded.getFilename())) {
                synologyActivity.renameSynologyFile(gallery.getGid(), downloaded.getFilename());
            }

            if (exactConfirmationVersion == Workflow.DEFAULT_VERSION) {
                // 旧历史保留原来的异常吞吐行为和 Activity 顺序。
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

        // ==========================================
        // 2. 开始轮询等待 Komga 扫描完成并入库
        // ==========================================
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
                log.error("[❌ Komga 匹配冲突] GID: {}, 原因: {}", gallery.getGid(), match.getReason());
                databaseActivity.recordKomgaConfirmation(gallery.getGid(),
                        DownloadStatus.KOMGA_IMPORT_FAILED.getValue(), match.getReason(), candidateBookIds);
                return;
            } else {
                databaseActivity.recordKomgaConfirmation(gallery.getGid(),
                        DownloadStatus.WAITING_KOMGA.getValue(), match.getReason(), candidateBookIds);
                int safeInterval = Math.max(1, pollIntervalSeconds);
                int rescanAttempt = Math.max(1, 120 / safeInterval);
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
