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

@Service
public class VisualFingerprintService {
    private final GalleryPageHashMapper pageHashMapper;
    private final VisualMatchMapper visualMatchMapper;

    public VisualFingerprintService(GalleryPageHashMapper pageHashMapper, VisualMatchMapper visualMatchMapper) {
        this.pageHashMapper = pageHashMapper;
        this.visualMatchMapper = visualMatchMapper;
    }

    @Transactional
    public int replace(Long gid, List<GalleryPageFingerprint> fingerprints) {
        if (gid == null || fingerprints == null || fingerprints.isEmpty()) return 0;
        boolean incomingPreview = fingerprints.stream().allMatch(item -> "PREVIEW".equals(item.getSource()));
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

    public boolean isCurrent(Long gid) {
        QueryWrapper<GalleryPageHashEntity> query = new QueryWrapper<>();
        query.eq("gid", gid).eq("algorithm_version", PerceptualHash.ALGORITHM_VERSION).last("LIMIT 1");
        return pageHashMapper.selectCount(query) > 0;
    }

    public boolean hasArchiveFingerprints(Long gid) {
        QueryWrapper<GalleryPageHashEntity> query = new QueryWrapper<>();
        query.eq("gid", gid).eq("algorithm_version", PerceptualHash.ALGORITHM_VERSION)
                .eq("source", "ARCHIVE").last("LIMIT 1");
        return pageHashMapper.selectCount(query) > 0;
    }

    public List<GalleryPageFingerprint> find(Long gid) {
        QueryWrapper<GalleryPageHashEntity> query = new QueryWrapper<>();
        query.eq("gid", gid).eq("algorithm_version", PerceptualHash.ALGORITHM_VERSION)
                .orderByAsc("page_index");
        return pageHashMapper.selectList(query).stream().map(this::toDto).toList();
    }

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
