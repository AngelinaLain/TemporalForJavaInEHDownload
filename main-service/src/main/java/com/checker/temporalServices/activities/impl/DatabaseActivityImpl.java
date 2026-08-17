package com.checker.temporalServices.activities.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.checker.common.Constants;
import com.checker.common.DownloadStatus;
import com.checker.common.GalleryDeduplication;
import com.checker.config.EhWorkflowConfig;
import com.checker.dto.WorkflowSettings;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.service.EhGalleriesService;
import com.checker.temporalServices.activities.DatabaseActivity;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 数据库 Activity 实现：负责画廊数据的增删改查操作
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = Constants.TASK_QUEUE)
public class DatabaseActivityImpl implements DatabaseActivity {

    @Autowired
    private EhGalleriesMapper galleriesMapper;
    @Autowired
    private EhGalleriesService galleriesService;
    @Autowired
    private EhWorkflowConfig workflowConfig;

    @Override
    public void saveToDatabase(EhGalleriesEntity gallery) {
        boolean isSuccess = galleriesService.saveOrUpdate(gallery);
        if (isSuccess) {
            log.info("✅ 画廊入库/更新成功, GID: {}", gallery.getGid());
        } else {
            log.warn("⚠️ 画廊入库/更新未能正常执行, GID: {}", gallery.getGid());
        }
    }

    @Override
    public void saveGalleriesBatch(List<EhGalleriesEntity> galleries) {
        if (galleries == null || galleries.isEmpty()) return;

        // 区分新记录与已有记录：
        // 新记录走 insertBatchSomeColumn（一次 INSERT 多行，性能优于逐条）；
        // 已有记录走 saveOrUpdateBatch（按主键更新）。
        List<Long> gids = galleries.stream()
                .filter(g -> g != null && g.getGid() != null)
                .map(EhGalleriesEntity::getGid)
                .distinct()
                .toList();
        Map<Long, EhGalleriesEntity> existingMap = gids.isEmpty()
                ? Collections.emptyMap()
                : galleriesMapper.selectBatchIds(gids).stream()
                        .collect(java.util.stream.Collectors.toMap(EhGalleriesEntity::getGid, Function.identity()));

        List<EhGalleriesEntity> toInsert = new ArrayList<>();
        List<EhGalleriesEntity> toUpdate = new ArrayList<>();
        for (EhGalleriesEntity gallery : galleries) {
            if (gallery == null || gallery.getGid() == null) continue;
            if (existingMap.containsKey(gallery.getGid())) {
                toUpdate.add(gallery);
            } else {
                toInsert.add(gallery);
            }
        }

        int inserted = 0;
        if (!toInsert.isEmpty()) {
            inserted = galleriesMapper.insertBatchSomeColumn(toInsert);
        }
        boolean updated = true;
        if (!toUpdate.isEmpty()) {
            updated = galleriesService.saveOrUpdateBatch(toUpdate);
        }
        log.info("✅ 批量入库完成，共 {} 条（新增 {} 条走批量 INSERT，更新 {} 条，结果: {}）",
                galleries.size(), inserted, toUpdate.size(), updated);
    }

    @Override
    public void updateGalleryDeduplicationMetadata(List<EhGalleriesEntity> galleries) {
        if (galleries == null || galleries.isEmpty()) return;

        int updated = 0;
        for (EhGalleriesEntity gallery : galleries) {
            if (gallery == null || gallery.getGid() == null || !GalleryDeduplication.isIdentifiable(gallery)) {
                continue;
            }
            EhGalleriesEntity updateEntity = new EhGalleriesEntity();
            updateEntity.setGid(gallery.getGid());
            updateEntity.setOriginalTitle(gallery.getOriginalTitle());
            updateEntity.setPageCount(gallery.getPageCount());
            updateEntity.setRating(gallery.getRating());
            updateEntity.setTags(gallery.getTags());
            updateEntity.setDedupeKey(gallery.getDedupeKey());
            updateEntity.setDedupeConfidence(gallery.getDedupeConfidence());
            galleriesMapper.updateById(updateEntity);
            updated++;
        }
        if (updated > 0) {
            log.info("🔖 已回填 {} 条已有画廊的作品指纹", updated);
        }
    }

    @Override
    public List<EhGalleriesEntity> findPreferredGalleriesByDedupeKeys(List<String> dedupeKeys) {
        if (dedupeKeys == null || dedupeKeys.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> uniqueKeys = dedupeKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
        if (uniqueKeys.isEmpty()) {
            return Collections.emptyList();
        }

        List<EhGalleriesEntity> results = new ArrayList<>();
        final int batchSize = 500;
        for (int offset = 0; offset < uniqueKeys.size(); offset += batchSize) {
            List<String> batch = uniqueKeys.subList(offset, Math.min(offset + batchSize, uniqueKeys.size()));
            QueryWrapper<EhGalleriesEntity> wrapper = new QueryWrapper<>();
            wrapper.in("dedupe_key", batch).isNull("duplicate_of_gid");
            results.addAll(galleriesMapper.selectList(wrapper));
        }
        return results;
    }
    @Override
    public void updateGalleryStatus(Long gid, String status) {
        EhGalleriesEntity updateEntity = new EhGalleriesEntity();
        updateEntity.setGid(gid);
        updateEntity.setDownloadStatus(status);
        galleriesMapper.updateById(updateEntity);
        log.info("📝 状态更新完成, GID: {}, 新状态: {}", gid, status);
    }

    @Override
    public List<EhGalleriesEntity> getFailedGalleries() {
        QueryWrapper<EhGalleriesEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("download_status", DownloadStatus.DOWNLOAD_FAILED.getValue(),
                DownloadStatus.DOWNLOADED.getValue(), DownloadStatus.PARTIAL.getValue());
        return galleriesMapper.selectList(queryWrapper);
    }

    @Override
    public EhGalleriesEntity getGalleryById(Long gid) {
        return galleriesMapper.selectById(gid);
    }

    @Override
    public List<EhGalleriesEntity> getGalleriesByIds(List<Long> gids) {
        if (gids == null || gids.isEmpty()) return Collections.emptyList();
        return galleriesMapper.selectBatchIds(gids);
    }

    @Override
    public WorkflowSettings loadWorkflowSettings() {
        return WorkflowSettings.builder()
                .maxConcurrency(workflowConfig.getMaxConcurrency())
                .komgaImportMaxRetries(workflowConfig.getKomgaImportMaxRetries())
                .komgaImportPollIntervalSeconds(workflowConfig.getKomgaImportPollIntervalSeconds())
                .downloadPollIntervalMinutes(workflowConfig.getDownloadPollIntervalMinutes())
                .downloadCooldownSeconds(workflowConfig.getDownloadCooldownSeconds())
                .build();
    }

    @Override
    public void updateGallerySize(Long gid, Double sizeMb) {
        if (gid == null || sizeMb == null) return;
        EhGalleriesEntity updateEntity = new EhGalleriesEntity();
        updateEntity.setGid(gid);
        updateEntity.setFileSizeMb(sizeMb);
        galleriesMapper.updateById(updateEntity);
        log.info("💾 大小更新完成, GID: {}, 预估大小: {} MB", gid, sizeMb);
    }

    @Override
    public void updateGallerySummary(Long gid, String summary) {
        if (gid == null || summary == null || summary.isBlank()) return;
        EhGalleriesEntity updateEntity = new EhGalleriesEntity();
        updateEntity.setGid(gid);
        updateEntity.setSummary(summary);
        galleriesMapper.updateById(updateEntity);
        log.info("🤖 AI 概述存库完成, GID: {}", gid);
    }
}
