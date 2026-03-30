package com.checker.temporalServices.workflows.impl;

import cn.hutool.core.util.StrUtil;
import com.checker.common.ErrorType;
import com.checker.entity.EhGalleriesEntity;
import com.checker.temporalServices.activities.DatabaseActivity;
import com.checker.temporalServices.activities.KomgaActivity;
import com.checker.temporalServices.activities.NotificationActivity;
import com.checker.temporalServices.activities.SynologyActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;

/**
 * Temporal 工作流共享工具类 — 非 Spring Bean，包内可见。
 * <p>
 * 集中管理：
 * <ul>
 *   <li>统一的 ActivityOptions 配置</li>
 *   <li>下载完成后的 Komga 入库前置三步骤（抓取元数据 → 文件重命名 → 触发扫描）</li>
 *   <li>Komga 异步轮询入库任务构建（20 次重试 × 15 秒）</li>
 * </ul>
 */
class WorkflowSteps {

    /** 所有 Activity Stub 共用的超时与重试策略 */
    static final ActivityOptions DEFAULT_OPTIONS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(10))
                    .setMaximumAttempts(3)
                    .setDoNotRetry(ErrorType.QUOTA_EXCEEDED.getCode(), ErrorType.IP_BANNED.getCode(), ErrorType.ARCHIVE_LINK_EXTRACT_FAILED.getCode(), ErrorType.SYNOLOGY_AUTH_FAILED.getCode())
                    .build())
            .build();

    /**
     * 下载完成后的 Komga 入库前置三步骤：
     * <ol>
     *   <li>向 EHentai 请求标签数据并写入数据库</li>
     *   <li>若数据库已存有文件名，则重命名群晖文件</li>
     *   <li>触发 Komga 库扫描</li>
     * </ol>
     */
    static void postDownloadKomgaProcess(
            KomgaActivity komgaActivity,
            DatabaseActivity databaseActivity,
            SynologyActivity synologyActivity,
            Long gid, String token) {
        komgaActivity.fetchAndSaveMetadata(gid, token);
        EhGalleriesEntity downloaded = databaseActivity.getGalleryById(gid);
        if (downloaded != null && StrUtil.isNotBlank(downloaded.getFilename())) {
            synologyActivity.renameSynologyFile(gid, downloaded.getFilename());
        }
        komgaActivity.triggerKomgaLibraryScan();
    }

    /**
     * 构建 Komga 异步轮询入库任务（最多重试 20 次，每次间隔 15 秒）。
     * 超时后通过 {@code notificationActivity} 发送邮件告警。
     *
     * @param gallery            目标画廊实体（需要 gid、title）
     * @param timeoutSubject     超时邮件主题
     * @param timeoutContent     超时邮件正文
     * @return Promise&lt;Void&gt; 供调用方加入 {@code Promise.allOf()} 等待
     */
    static Promise<Void> buildKomgaImportTask(
            KomgaActivity komgaActivity,
            NotificationActivity notificationActivity,
            EhGalleriesEntity gallery,
            Logger log,
            String timeoutSubject,
            String timeoutContent) {
        return Async.procedure(() -> {
            boolean isImportedToKomga = false;
            int maxRetries = 20;
            int currentTry = 0;
            while (!isImportedToKomga && currentTry < maxRetries) {
                Workflow.sleep(Duration.ofSeconds(15));
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
        });
    }

    private WorkflowSteps() {}
}
