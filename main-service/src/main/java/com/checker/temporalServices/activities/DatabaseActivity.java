package com.checker.temporalServices.activities;

import com.checker.dto.WorkflowSettings;
import com.checker.dto.GalleryPageFingerprint;
import com.checker.entity.EhGalleriesEntity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;
import java.util.Map;

/**
 * 数据库域 Activity：负责所有对 eh_galleries 表的读写操作
 */
@ActivityInterface
public interface DatabaseActivity {

    /**
     * 幂等写入（存在则更新，不存在则插入）
     */
    @ActivityMethod
    void saveToDatabase(EhGalleriesEntity gallery);

    /**
     * 批量幂等写入（MyBatis-Plus saveOrUpdateBatch）
     */
    @ActivityMethod
    void saveGalleriesBatch(List<EhGalleriesEntity> galleries);

    /**
     * 为已有画廊回填 gdata 元数据和作品指纹，不修改其下载状态。
     */
    void updateGalleryDeduplicationMetadata(List<EhGalleriesEntity> galleries);

    /**
     * 分批回填升级前历史记录的候选键。无法安全识别的记录也会写入当前算法版本，
     * 避免后续工作流反复扫描。
     */
    void backfillGalleryDeduplicationMetadata();

    /** 返回尚未生成当前版本视觉指纹的本轮画廊。 */
    List<EhGalleriesEntity> findGalleriesNeedingVisualFingerprint(List<Long> gids);

    /** 持久化下载前预览图指纹；已有归档级指纹时不会被低质量预览覆盖。 */
    void saveGalleryVisualFingerprints(Map<Long, List<GalleryPageFingerprint>> fingerprints);

    /**
     * 查询指定作品指纹中已存在的首选版本（不含被标记为重复的版本）。
     */
    List<EhGalleriesEntity> findPreferredGalleriesByDedupeKeys(List<String> dedupeKeys);

    /**
     * 原子判断该画廊是否是候选组当前应下载的版本。方法内部按 candidate_key 加数据库锁，
     * 同时写入首选关系、匹配分数/理由，并把获准版本置为 DOWNLOADING。
     */
    boolean claimGalleryForDownload(Long gid);

    /** 子流程结束后根据最终状态重新选择首选版本，失败版本不会压住健康版本。 */
    void reconcileGalleryDeduplication(List<String> candidateKeys);

    /**
     * 更新指定画廊的下载状态
     */
    @ActivityMethod
    void updateGalleryStatus(Long gid, String status);

    /**
     * 持久化 Komga 入库确认进度，供人工复核页面查看。
     */
    @ActivityMethod
    void recordKomgaConfirmation(Long gid, String status, String reason, String candidateBookIds);

    /**
     * 查询所有下载失败或已下载但未入库的画廊
     */
    @ActivityMethod
    List<EhGalleriesEntity> getFailedGalleries();

    /**
     * 按 GID 查询单条画廊记录
     */
    @ActivityMethod
    EhGalleriesEntity getGalleryById(Long gid);

    /**
     * 批量按 GID 查询画廊记录
     */
    @ActivityMethod
    List<EhGalleriesEntity> getGalleriesByIds(List<Long> gids);

    /**
     * 从 application.yaml 加载工作流运行时配置
     */
    @ActivityMethod
    WorkflowSettings loadWorkflowSettings();

    @ActivityMethod
    void updateGallerySize(Long gid, Double sizeMb);

    /**
     * 更新 AI 生成的内容概述
     */
    @ActivityMethod
    void updateGallerySummary(Long gid, String summary);
}
