package com.checker.temporalServices.activities.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.checker.common.Constants;
import com.checker.common.DownloadStatus;
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

import java.util.Collections;
import java.util.List;

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
        boolean success = galleriesService.saveOrUpdateBatch(galleries);
        log.info("✅ 批量入库完成，共 {} 条，结果: {}", galleries.size(), success);
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
        queryWrapper.in("download_status", DownloadStatus.DOWNLOAD_FAILED.getValue(), DownloadStatus.DOWNLOADED.getValue());
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
}
