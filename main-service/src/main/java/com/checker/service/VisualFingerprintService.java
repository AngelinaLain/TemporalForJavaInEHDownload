package com.checker.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.checker.common.GalleryVisualMatching;
import com.checker.common.PerceptualHash;
import com.checker.dto.GalleryPageFingerprint;
import com.checker.dto.GalleryVisualMatch;
import com.checker.entity.GalleryPageHashEntity;
import com.checker.entity.VisualMatchEntity;
import com.checker.mapper.GalleryPageHashMapper;
import com.checker.mapper.VisualMatchMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 保存和读取画廊页面指纹，比较两本画廊后持久化可复用的视觉匹配证据。 */
@Service
public class VisualFingerprintService {
    private final GalleryPageHashMapper pageHashMapper;
    private final VisualMatchMapper visualMatchMapper;

    public VisualFingerprintService(GalleryPageHashMapper pageHashMapper, VisualMatchMapper visualMatchMapper) {
        this.pageHashMapper = pageHashMapper;
        this.visualMatchMapper = visualMatchMapper;
    }

    /** 在事务中替换某画廊当前算法版本的页面指纹；已有归档指纹时不让预览指纹覆盖它。 */
    @Transactional
    public int replace(Long gid, List<GalleryPageFingerprint> fingerprints) {
        if (gid == null || fingerprints == null || fingerprints.isEmpty()) return 0;
        boolean incomingPreview = fingerprints.stream().allMatch(item -> "PREVIEW".equals(item.getSource()));
        // 归档页来自实际文件，比源站预览页更完整，因此已有归档证据时保留它。
        if (incomingPreview && hasArchiveFingerprints(gid)) return 0;
        QueryWrapper<GalleryPageHashEntity> delete = new QueryWrapper<>();
        delete.eq("gid", gid).eq("algorithm_version", PerceptualHash.ALGORITHM_VERSION);
        pageHashMapper.delete(delete);
        int inserted = 0;
        for (GalleryPageFingerprint fingerprint : fingerprints) {
            if (fingerprint == null || fingerprint.getPerceptualHash() == null || fingerprint.getCenterHash() == null) continue;
            fingerprint.setGid(gid);
            fingerprint.setAlgorithmVersion(PerceptualHash.ALGORITHM_VERSION);
            pageHashMapper.insert(toEntity(fingerprint));
            inserted++;
        }
        return inserted;
    }

    /** 检查画廊是否已有当前算法版本的指纹。 */
    public boolean isCurrent(Long gid) {
        QueryWrapper<GalleryPageHashEntity> query = new QueryWrapper<>();
        query.eq("gid", gid).eq("algorithm_version", PerceptualHash.ALGORITHM_VERSION).last("LIMIT 1");
        return pageHashMapper.selectCount(query) > 0;
    }

    /** 检查是否已从真实归档提取过页面指纹，避免把源站预览指纹混用。 */
    public boolean hasArchiveFingerprints(Long gid) {
        QueryWrapper<GalleryPageHashEntity> query = new QueryWrapper<>();
        query.eq("gid", gid).eq("algorithm_version", PerceptualHash.ALGORITHM_VERSION)
                .eq("source", "ARCHIVE").last("LIMIT 1");
        return pageHashMapper.selectCount(query) > 0;
    }

    /** 按页顺序读取已保存的指纹，并转换为比较用数据。 */
    public List<GalleryPageFingerprint> find(Long gid) {
        QueryWrapper<GalleryPageHashEntity> query = new QueryWrapper<>();
        query.eq("gid", gid).eq("algorithm_version", PerceptualHash.ALGORITHM_VERSION)
                .orderByAsc("page_index");
        return pageHashMapper.selectList(query).stream().map(this::toDto).toList();
    }

    /** 比较两个画廊的页面指纹并保存匹配证据，供选优和人工审核复用。 */
    @Transactional
    public GalleryVisualMatch matchAndPersist(Long firstGid, Long secondGid) {
        long leftGid = Math.min(firstGid, secondGid);
        long rightGid = Math.max(firstGid, secondGid);
        GalleryVisualMatch match = GalleryVisualMatching.match(
                leftGid, find(leftGid), rightGid, find(rightGid));
        QueryWrapper<VisualMatchEntity> query = new QueryWrapper<>();
        query.eq("left_gid", leftGid).eq("right_gid", rightGid)
                .eq("algorithm_version", PerceptualHash.ALGORITHM_VERSION);
        VisualMatchEntity entity = visualMatchMapper.selectOne(query);
        if (entity == null) {
            entity = new VisualMatchEntity();
            copy(match, entity);
            visualMatchMapper.insert(entity);
        } else {
            Long id = entity.getId();
            copy(match, entity);
            entity.setId(id);
            visualMatchMapper.updateById(entity);
        }
        return match;
    }

    /** 统计具有当前算法版本指纹的画廊数量。 */
    public long countCurrentGalleries() {
        QueryWrapper<GalleryPageHashEntity> query = new QueryWrapper<>();
        query.select("DISTINCT gid").eq("algorithm_version", PerceptualHash.ALGORITHM_VERSION);
        return pageHashMapper.selectObjs(query).size();
    }

    private GalleryPageHashEntity toEntity(GalleryPageFingerprint dto) {
        GalleryPageHashEntity entity = new GalleryPageHashEntity();
        entity.setGid(dto.getGid());
        entity.setPageIndex(dto.getPageIndex());
        entity.setPageName(dto.getPageName());
        entity.setSource(dto.getSource());
        entity.setPerceptualHash(dto.getPerceptualHash());
        entity.setCenterHash(dto.getCenterHash());
        entity.setQuality(dto.getQuality());
        entity.setWidth(dto.getWidth());
        entity.setHeight(dto.getHeight());
        entity.setAlgorithmVersion(dto.getAlgorithmVersion());
        return entity;
    }

    private GalleryPageFingerprint toDto(GalleryPageHashEntity entity) {
        return GalleryPageFingerprint.builder()
                .gid(entity.getGid()).pageIndex(entity.getPageIndex()).pageName(entity.getPageName())
                .source(entity.getSource()).perceptualHash(entity.getPerceptualHash())
                .centerHash(entity.getCenterHash()).quality(entity.getQuality())
                .width(entity.getWidth()).height(entity.getHeight())
                .algorithmVersion(entity.getAlgorithmVersion()).build();
    }

    private void copy(GalleryVisualMatch source, VisualMatchEntity target) {
        target.setLeftGid(source.getLeftGid());
        target.setRightGid(source.getRightGid());
        target.setSimilarity(source.getSimilarity());
        target.setMatchedPages(source.getMatchedPages());
        target.setLeftSamples(source.getLeftSamples());
        target.setRightSamples(source.getRightSamples());
        target.setSampleCoverage(source.getSampleCoverage());
        target.setOrderConsistency(source.getOrderConsistency());
        target.setRecommendedGid(source.getRecommendedGid());
        target.setQualityDelta(source.getQualityDelta());
        target.setReason(source.getReason());
        target.setAlgorithmVersion(source.getAlgorithmVersion());
    }
}
