package com.checker.temporalServices.activities.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.checker.common.Constants;
import com.checker.common.DownloadStatus;
import com.checker.common.GalleryDeduplication;
import com.checker.config.EhWorkflowConfig;
import com.checker.dto.WorkflowSettings;
import com.checker.entity.DedupeReviewEntity;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.service.EhGalleriesService;
import com.checker.service.DedupeReviewService;
import com.checker.temporalServices.activities.DatabaseActivity;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 数据库 Activity 实现：负责画廊数据的增删改查操作
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = Constants.TASK_QUEUE)
public class DatabaseActivityImpl implements DatabaseActivity {

    private static final String DEDUPE_BACKFILL_LOCK_KEY = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final int DEDUPE_BACKFILL_BATCH_SIZE = 500;

    @Autowired
    private EhGalleriesMapper galleriesMapper;
    @Autowired
    private EhGalleriesService galleriesService;
    @Autowired
    private EhWorkflowConfig workflowConfig;
    @Autowired
    private DedupeReviewService dedupeReviewService;

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
            // insertBatchSomeColumn 不会触发 MetaObjectHandler 自动填充，
            // 且会原样写入 null 值，故插入前兜底补全时间字段（crawled_at 为 NOT NULL）。
            Date now = new Date();
            for (EhGalleriesEntity gallery : toInsert) {
                if (gallery.getCrawledAt() == null) {
                    gallery.setCrawledAt(now);
                }
                if (gallery.getUpdatedAt() == null) {
                    gallery.setUpdatedAt(now);
                }
            }
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
            updateEntity.setCandidateKey(gallery.getCandidateKey());
            updateEntity.setDedupeConfidence(gallery.getDedupeConfidence());
            updateEntity.setDedupeAlgorithmVersion(gallery.getDedupeAlgorithmVersion());
            galleriesMapper.updateById(updateEntity);
            updated++;
        }
        if (updated > 0) {
            log.info("🔖 已回填 {} 条已有画廊的作品指纹", updated);
        }
    }

    @Override
    @Transactional
    public void backfillGalleryDeduplicationMetadata() {
        // 多个父流程可能同时启动；用专用锁确保历史回填只由一个事务执行。
        lockCandidateKey(DEDUPE_BACKFILL_LOCK_KEY);
        int updated = 0;
        while (true) {
            QueryWrapper<EhGalleriesEntity> query = new QueryWrapper<>();
            query.isNull("candidate_key")
                    .and(wrapper -> wrapper.isNull("dedupe_algorithm_version")
                            .or().lt("dedupe_algorithm_version", GalleryDeduplication.ALGORITHM_VERSION))
                    .orderByAsc("gid")
                    .last("LIMIT " + DEDUPE_BACKFILL_BATCH_SIZE);
            List<EhGalleriesEntity> batch = galleriesMapper.selectList(query);
            if (batch.isEmpty()) break;

            for (EhGalleriesEntity gallery : batch) {
                GalleryDeduplication.populateIdentity(gallery);
                UpdateWrapper<EhGalleriesEntity> update = new UpdateWrapper<>();
                update.eq("gid", gallery.getGid())
                        .set("dedupe_key", gallery.getDedupeKey())
                        .set("candidate_key", gallery.getCandidateKey())
                        .set("dedupe_confidence", gallery.getDedupeConfidence())
                        .set("dedupe_algorithm_version", GalleryDeduplication.ALGORITHM_VERSION);
                galleriesMapper.update(null, update);
                updated++;
            }
        }
        if (updated > 0) {
            log.info("🔖 已使用去重算法 V{} 回填 {} 条历史画廊", GalleryDeduplication.ALGORITHM_VERSION, updated);
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
    @Transactional
    public boolean claimGalleryForDownload(Long gid) {
        if (gid == null) return false;
        EhGalleriesEntity requested = galleriesMapper.selectById(gid);
        if (requested == null) return false;

        String candidateKey = requested.getCandidateKey();
        if (candidateKey == null || candidateKey.isBlank()) {
            return markUnidentifiedGalleryDownloading(requested);
        }

        lockCandidateKey(candidateKey);
        List<EhGalleriesEntity> bucket = selectByCandidateKey(candidateKey);
        dedupeReviewService.ensureGrayZoneReviews(candidateKey, bucket);
        List<DedupeReviewEntity> reviews = dedupeReviewService.findByCandidateKey(candidateKey);
        Set<Long> heldGids = dedupeReviewService.heldGids(bucket, reviews);
        if (heldGids.contains(gid)) {
            markReviewRequired(requested, reviews);
            log.info("🧑‍⚖️ GID {} 存在 65-84 分的灰区匹配，等待人工审核", gid);
            return false;
        }
        List<EhGalleriesEntity> available = bucket.stream()
                .filter(item -> !heldGids.contains(item.getGid()))
                .toList();
        List<EhGalleriesEntity> component = componentContaining(available, gid, reviews);
        if (component.isEmpty()) component = List.of(requested);

        // 已有同作品版本正在下载时优先沿用，避免并发父流程重复下载。
        EhGalleriesEntity active = component.stream()
                .filter(item -> !gid.equals(item.getGid()))
                .filter(item -> hasStatus(item, DownloadStatus.DOWNLOADING))
                .max(Comparator.comparingLong(EhGalleriesEntity::getGid))
                .orElse(null);
        if (active != null) {
            applyDecision(component, active, null);
            markRejectedCandidate(requested, active);
            log.info("🔒 GID {} 与正在下载的 GID {} 匹配，本次不重复派发", gid, active.getGid());
            return false;
        }

        List<EhGalleriesEntity> eligible = component.stream()
                .filter(item -> gid.equals(item.getGid()) || isHealthyOrPending(item))
                .toList();
        EhGalleriesEntity preferred = dedupeReviewService.choosePreferred(
                eligible.isEmpty() ? component : eligible, reviews);
        if (!gid.equals(preferred.getGid())) {
            applyDecision(component, preferred, null);
            markRejectedCandidate(requested, preferred);
            log.info("🔁 GID {} 匹配已有/更优版本 GID {}，跳过下载", gid, preferred.getGid());
            return false;
        }

        applyDecision(component, preferred, gid);
        log.info("✅ GID {} 已原子认领为候选组首选下载版本", gid);
        return true;
    }

    @Override
    @Transactional
    public void reconcileGalleryDeduplication(List<String> candidateKeys) {
        if (candidateKeys == null || candidateKeys.isEmpty()) return;
        List<String> keys = candidateKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .sorted()
                .toList();
        // 固定顺序获取行锁，避免两个批次以不同顺序处理时产生死锁。
        keys.forEach(this::lockCandidateKey);
        for (String key : keys) {
            List<DedupeReviewEntity> reviews = dedupeReviewService.findByCandidateKey(key);
            List<EhGalleriesEntity> bucket = selectByCandidateKey(key);
            Set<Long> heldGids = dedupeReviewService.heldGids(bucket, reviews);
            List<EhGalleriesEntity> available = bucket.stream()
                    .filter(item -> !heldGids.contains(item.getGid()))
                    .toList();
            for (List<EhGalleriesEntity> component : dedupeReviewService.cluster(available, reviews)) {
                if (component.isEmpty()) continue;
                List<EhGalleriesEntity> active = component.stream()
                        .filter(item -> hasStatus(item, DownloadStatus.DOWNLOADING))
                        .toList();
                List<EhGalleriesEntity> healthy = component.stream()
                        .filter(this::isHealthyCompleted)
                        .toList();
                List<EhGalleriesEntity> pending = component.stream()
                        .filter(item -> hasStatus(item, DownloadStatus.PENDING))
                        .toList();
                List<EhGalleriesEntity> pool = !active.isEmpty() ? active
                        : (!healthy.isEmpty() ? healthy : (!pending.isEmpty() ? pending : component));
                EhGalleriesEntity preferred = dedupeReviewService.choosePreferred(pool, reviews);
                applyDecision(component, preferred, null);
            }
        }
        log.info("🔄 已重新核对 {} 个候选键的首选版本关系", keys.size());
    }

    private boolean markUnidentifiedGalleryDownloading(EhGalleriesEntity gallery) {
        if (hasStatus(gallery, DownloadStatus.IMPORTED)
                || hasStatus(gallery, DownloadStatus.DOWNLOADING)
                || hasStatus(gallery, DownloadStatus.WAITING_KOMGA)
                || hasStatus(gallery, DownloadStatus.KOMGA_IMPORT_FAILED)
                || hasStatus(gallery, DownloadStatus.BLOCKED)) {
            return false;
        }
        UpdateWrapper<EhGalleriesEntity> update = new UpdateWrapper<>();
        update.eq("gid", gallery.getGid())
                .set("download_status", DownloadStatus.DOWNLOADING.getValue())
                .set("duplicate_of_gid", null)
                .set("dedupe_match_score", null)
                .set("dedupe_match_reason", "元数据不足，未参与自动作品归组");
        return galleriesMapper.update(null, update) == 1;
    }

    private void markReviewRequired(EhGalleriesEntity gallery, List<DedupeReviewEntity> reviews) {
        if (isHealthyCompleted(gallery) || hasStatus(gallery, DownloadStatus.DOWNLOADING)) return;
        DedupeReviewEntity pending = reviews.stream()
                .filter(review -> DedupeReviewService.PENDING.equals(review.getDecision()))
                .filter(review -> gallery.getGid().equals(review.getLeftGid())
                        || gallery.getGid().equals(review.getRightGid()))
                .max(Comparator.comparingInt(DedupeReviewEntity::getMatchScore))
                .orElse(null);
        UpdateWrapper<EhGalleriesEntity> update = new UpdateWrapper<>();
        update.eq("gid", gallery.getGid())
                .set("download_status", DownloadStatus.REVIEW_REQUIRED.getValue())
                .set("duplicate_of_gid", null)
                .set("dedupe_match_score", pending == null ? null : pending.getMatchScore())
                .set("dedupe_match_reason", pending == null
                        ? "候选版本需要人工审核"
                        : "待人工审核：" + pending.getMatchReason());
        galleriesMapper.update(null, update);
    }

    private void lockCandidateKey(String candidateKey) {
        galleriesMapper.ensureDedupeLock(candidateKey);
        galleriesMapper.lockDedupeCandidate(candidateKey);
    }

    private List<EhGalleriesEntity> selectByCandidateKey(String candidateKey) {
        QueryWrapper<EhGalleriesEntity> query = new QueryWrapper<>();
        query.eq("candidate_key", candidateKey).orderByAsc("gid");
        return galleriesMapper.selectList(query);
    }

    private List<EhGalleriesEntity> componentContaining(List<EhGalleriesEntity> bucket, Long gid,
                                                        List<DedupeReviewEntity> reviews) {
        return dedupeReviewService.cluster(bucket, reviews).stream()
                .filter(group -> group.stream().anyMatch(item -> gid.equals(item.getGid())))
                .findFirst()
                .orElse(List.of());
    }

    private void applyDecision(List<EhGalleriesEntity> component, EhGalleriesEntity preferred,
                               Long claimedGid) {
        for (EhGalleriesEntity member : component) {
            boolean isPreferred = preferred.getGid().equals(member.getGid());
            GalleryDeduplication.MatchResult match = isPreferred
                    ? new GalleryDeduplication.MatchResult(100, "候选组首选版本")
                    : GalleryDeduplication.match(preferred, member);
            UpdateWrapper<EhGalleriesEntity> update = new UpdateWrapper<>();
            update.eq("gid", member.getGid())
                    .set("duplicate_of_gid", isPreferred ? null : preferred.getGid())
                    .set("dedupe_match_score", match.score())
                    .set("dedupe_match_reason", match.reason())
                    .set("dedupe_algorithm_version", GalleryDeduplication.ALGORITHM_VERSION);
            if (claimedGid != null && claimedGid.equals(member.getGid())) {
                update.set("download_status", DownloadStatus.DOWNLOADING.getValue());
            } else if (!isPreferred && hasStatus(member, DownloadStatus.PENDING)) {
                update.set("download_status", DownloadStatus.IGNORED.getValue());
            }
            galleriesMapper.update(null, update);
        }
    }

    private void markRejectedCandidate(EhGalleriesEntity requested, EhGalleriesEntity preferred) {
        GalleryDeduplication.MatchResult match = GalleryDeduplication.match(preferred, requested);
        UpdateWrapper<EhGalleriesEntity> update = new UpdateWrapper<>();
        update.eq("gid", requested.getGid())
                .set("duplicate_of_gid", preferred.getGid())
                .set("dedupe_match_score", match.score())
                .set("dedupe_match_reason", match.reason())
                .set("dedupe_algorithm_version", GalleryDeduplication.ALGORITHM_VERSION)
                .set("download_status", DownloadStatus.IGNORED.getValue());
        galleriesMapper.update(null, update);
    }

    private boolean isHealthyOrPending(EhGalleriesEntity gallery) {
        return isHealthyCompleted(gallery) || hasStatus(gallery, DownloadStatus.PENDING);
    }

    private boolean isHealthyCompleted(EhGalleriesEntity gallery) {
        return hasStatus(gallery, DownloadStatus.IMPORTED)
                || hasStatus(gallery, DownloadStatus.DOWNLOADED)
                || hasStatus(gallery, DownloadStatus.WAITING_KOMGA)
                || hasStatus(gallery, DownloadStatus.KOMGA_IMPORT_FAILED);
    }

    private static boolean hasStatus(EhGalleriesEntity gallery, DownloadStatus status) {
        return gallery != null && (status.getValue().equals(gallery.getDownloadStatus())
                || status.name().equals(gallery.getDownloadStatus()));
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
    public void recordKomgaConfirmation(Long gid, String status, String reason, String candidateBookIds) {
        if (gid == null) return;
        UpdateWrapper<EhGalleriesEntity> update = new UpdateWrapper<>();
        update.eq("gid", gid)
                .set("download_status", status)
                .setSql("komga_confirmation_attempts = COALESCE(komga_confirmation_attempts, 0) + 1")
                .set("komga_last_confirmation_at", new Date())
                .set("komga_confirmation_reason", truncate(reason, 1000))
                .set("komga_candidate_book_ids", truncate(candidateBookIds, 2000));
        galleriesMapper.update(null, update);
        log.info("📝 Komga 确认记录完成, GID: {}, 状态: {}, 原因: {}", gid, status, reason);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    @Override
    public List<EhGalleriesEntity> getFailedGalleries() {
        QueryWrapper<EhGalleriesEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("download_status", DownloadStatus.DOWNLOAD_FAILED.getValue(),
                DownloadStatus.DOWNLOADED.getValue(), DownloadStatus.KOMGA_IMPORT_FAILED.getValue(),
                DownloadStatus.PARTIAL.getValue());
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
                .downloadMode(workflowConfig.getDownloadMode())
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
