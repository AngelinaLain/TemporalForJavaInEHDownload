package com.checker.temporalServices.workflows.impl;

import cn.hutool.core.util.StrUtil;
import com.checker.common.Constants;
import com.checker.entity.EhGalleriesEntity;
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

    @Override
    public void waitForImport(EhGalleriesEntity gallery, int maxRetries, int pollIntervalSeconds, String timeoutSubject, String timeoutContent) {

        // ==========================================
        // 1. 核心修复：执行原来的 postDownloadKomgaProcess 逻辑
        // ==========================================
        try {
            // 拉取 EHentai 标签并存库
            komgaActivity.fetchAndSaveMetadata(gallery.getGid(), gallery.getToken());

            // 重命名群晖文件
            EhGalleriesEntity downloaded = databaseActivity.getGalleryById(gallery.getGid());
            if (downloaded != null && StrUtil.isNotBlank(downloaded.getFilename())) {
                synologyActivity.renameSynologyFile(gallery.getGid(), downloaded.getFilename());
            }

            // 触发 Komga 扫描
            komgaActivity.triggerKomgaLibraryScan();
        } catch (Exception e) {
            log.error("执行 Komga 入库前置动作(改名/触发扫描)失败，GID: {}", gallery.getGid(), e);
        }

        // ==========================================
        // 2. 开始轮询等待 Komga 扫描完成并入库
        // ==========================================
        boolean isImportedToKomga = false;
        int currentTry = 0;

        while (!isImportedToKomga && currentTry < maxRetries) {
            Workflow.sleep(Duration.ofSeconds(pollIntervalSeconds));
            currentTry++;
            String komgaSeriesId = komgaActivity.findBookInKomga(gallery.getGid());
            if (komgaSeriesId != null) {
                komgaActivity.pushMetadataToKomga(komgaSeriesId, gallery.getGid());
                isImportedToKomga = true;
            }
        }

        if (!isImportedToKomga) {
            log.warn("[⚠️ Komga 入库超时] GID: {}, 标题: {}", gallery.getGid(), gallery.getTitle());
            notificationActivity.sendEmailAlert(timeoutSubject, timeoutContent);
        }
    }
}
