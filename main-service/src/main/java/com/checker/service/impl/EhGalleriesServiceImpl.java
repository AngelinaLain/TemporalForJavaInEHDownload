package com.checker.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.checker.clients.SynologyApiClient;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.service.EhGalleriesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 画廊业务服务实现类
 */
@Slf4j
@Service
public class EhGalleriesServiceImpl extends ServiceImpl<EhGalleriesMapper, EhGalleriesEntity> implements EhGalleriesService {
    @Autowired
    private SynologyApiClient synologyApiClient;

    @Autowired
    @Qualifier("backgroundTaskExecutor")
    private TaskExecutor backgroundTaskExecutor;
    @Override
    public void batchUpdateFileSizes() {
        log.info("🚀 开始后台批量获取并补全画廊文件大小...");

        backgroundTaskExecutor.execute(() -> {
            // 1. 查找所有 fileSizeMb 为空或为 0，并且存在文件名的记录
            QueryWrapper<EhGalleriesEntity> wrapper = new QueryWrapper<>();
            wrapper.isNotNull("filename")
                    .ne("filename", "")
                    .and(w -> w.isNull("file_size_mb").or().eq("file_size_mb", 0));

            List<EhGalleriesEntity> list = this.list(wrapper);
            log.info("🔍 共查找到 {} 本需要补全文件大小的画廊记录", list.size());

            if (list.isEmpty()) return;

            // 2. 一次 SSH 连接批量查出所有 GID 的文件大小
            List<Long> gids = list.stream().map(EhGalleriesEntity::getGid).collect(Collectors.toList());
            Map<Long, Double> sizeMap = synologyApiClient.batchGetFileSizeViaSSH(gids);
            log.info("📦 SSH 批量查询完成，获取到 {}/{} 个文件大小", sizeMap.size(), gids.size());

            // 3. 逐条更新数据库
            int successCount = 0;
            for (EhGalleriesEntity gallery : list) {
                Double sizeMb = sizeMap.get(gallery.getGid());
                if (sizeMb != null && sizeMb > 0) {
                    try {
                        EhGalleriesEntity updateEntity = new EhGalleriesEntity();
                        updateEntity.setGid(gallery.getGid());
                        updateEntity.setFileSizeMb(sizeMb);

                        this.updateById(updateEntity);
                        successCount++;
                        log.info("💾 大小补全完成, GID: {}, 文件: {}, 大小: {} MB", gallery.getGid(), gallery.getFilename(), sizeMb);
                    } catch (Exception e) {
                        log.error("❌ 更新文件大小失败, GID: {}", gallery.getGid(), e);
                    }
                }
            }
            log.info("🎉 批量补全文件大小任务彻底完成！成功更新 {}/{} 个记录", successCount, list.size());
        });
    }
}
