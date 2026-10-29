package com.checker.temporalServices.activities.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.checker.common.Constants;
import com.checker.common.DownloadStatus;
import com.checker.common.GalleryDeduplication;
import com.checker.common.DedupeSettings;
import com.checker.config.EhWorkflowConfig;
import com.checker.dto.WorkflowSettings;
import com.checker.dto.GalleryPageFingerprint;
import com.checker.entity.DedupeReviewEntity;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.service.EhGalleriesService;
import com.checker.service.DedupeReviewService;
import com.checker.service.VisualFingerprintService;
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
 * 执行工作流中的数据库操作，并在事务内协调候选分组、人工审核和下载认领。
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
    @Autowired
    private VisualFingerprintService visualFingerprintService;

    /**
     * 补齐插入所需默认值后保存单条画廊。
     * */
    @Override
    public void saveToDatabase(EhGalleriesEntity gallery) {
        normalizeInsertDefaults(gallery);
        boolean isSuccess = galleriesService.saveOrUpdate(gallery);
        if (isSuccess) {
            log.info("✅ 画廊入库/更新成功, GID: {}", gallery.getGid());
        } else {
            log.warn("⚠️ 画廊入库/更新未能正常执行, GID: {}", gallery.getGid());
        }
    }

    /**
     * 先区分新增和已有记录，再分开批量插入或更新，减少数据库往返。
     * */
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
                normalizeInsertDefaults(gallery, now);
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

    /**
     * 自定义批量插入会显式写入映射列，不能只依赖数据库默认值。
     * 插入前补齐时间和确认次数，兼容尚未更新默认约束的历史数据库。
     */
    private void normalizeInsertDefaults(EhGalleriesEntity gallery) {
        normalizeInsertDefaults(gallery, new Date());
    }

    private void normalizeInsertDefaults(EhGalleriesEntity gallery, Date now) {
        if (gallery == null) return;
        if (gallery.getCrawledAt() == null) {
            gallery.setCrawledAt(now);
        }
        if (gallery.getUpdatedAt() == null) {
            gallery.setUpdatedAt(now);
        }
        if (gallery.getKomgaConfirmationAttempts() == null) {
            gallery.setKomgaConfirmationAttempts(0);
        }
    }

    /**
     * 只为可识别的历史记录回填标题、标签及去重特征，保留其他业务字段。
     * */
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

    /**
     * 持有专用锁后分批补齐旧版候选特征，避免多个采集流程同时执行历史回填。
     * */
    @Override
    @Transactional
    public void backfillGalleryDeduplicationMetadata() {
        /* 多个父流程可能同时启动；用专用锁确保历史回填只由一个事务执行。 */
        /* 确保候选锁记录存在并获取行锁，让同组判断和状态修改串行执行。 */
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

    /**
     * 只找本轮涉及且确有多个版本的候选组，并过滤已有当前版本指纹的记录。
     * */
    @Override
    public List<EhGalleriesEntity> findGalleriesNeedingVisualFingerprint(List<Long> gids) {
        if (gids == null || gids.isEmpty()) return List.of();
        List<String> incomingKeys = galleriesMapper.selectBatchIds(gids).stream()
                .map(EhGalleriesEntity::getCandidateKey)
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
        if (incomingKeys.isEmpty()) return List.of();
        QueryWrapper<EhGalleriesEntity> query = new QueryWrapper<>();
        query.in("candidate_key", incomingKeys).orderByAsc("gid");
        List<EhGalleriesEntity> bucketMembers = galleriesMapper.selectList(query);
        Map<String, Long> counts = bucketMembers.stream().collect(java.util.stream.Collectors.groupingBy(
                EhGalleriesEntity::getCandidateKey, java.util.stream.Collectors.counting()));
        // 单例桶无需远程拉取图片；当第二个版本出现时，会同时补算新旧两个版本。
        return bucketMembers.stream()
                .filter(gallery -> counts.getOrDefault(gallery.getCandidateKey(), 0L) > 1)
                .filter(gallery -> !visualFingerprintService.isCurrent(gallery.getGid()))
                .toList();
    }

    /**
     * 将采样结果交给指纹服务保存，统一处理新旧指纹的替换规则。
     * */
    @Override
    public void saveGalleryVisualFingerprints(Map<Long, List<GalleryPageFingerprint>> fingerprints) {
        if (fingerprints == null || fingerprints.isEmpty()) return;
        int pages = 0;
        for (Map.Entry<Long, List<GalleryPageFingerprint>> entry : fingerprints.entrySet()) {
            pages += visualFingerprintService.replace(entry.getKey(), entry.getValue());
        }
        log.info("🖼️ 已保存 {} 个画廊页面视觉指纹", pages);
    }

    /**
     * 对旧版去重键去重后分批读取首选记录，供旧历史分支继续执行。
     * */
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

    /**
     * 锁住候选组后结合人工审核结果选择首选版本，并在同一事务中认领下载，防止并发重复派发。
     * */
    @Override
    @Transactional
    public boolean claimGalleryForDownload(Long gid) {
        if (gid == null) return false;
        EhGalleriesEntity requested = galleriesMapper.selectById(gid);
        if (requested == null) return false;

        String candidateKey = requested.getCandidateKey();
        if (candidateKey == null || candidateKey.isBlank()) {
            /* 没有可靠候选特征时只认领当前画廊，并避免覆盖正在处理或已完成的记录。 */
            return markUnidentifiedGalleryDownloading(requested);
        }

        lockCandidateKey(candidateKey);
        // 本次认领只读取一次参数，审核、待审扩展、聚类和写回分数始终采用同一套标准。
        DedupeSettings settings = dedupeReviewService.settingsSnapshot();
        List<EhGalleriesEntity> bucket = selectByCandidateKey(candidateKey);
        dedupeReviewService.ensureGrayZoneReviews(candidateKey, bucket, settings);
        List<DedupeReviewEntity> reviews = dedupeReviewService.findByCandidateKey(candidateKey);
        Set<Long> heldGids = dedupeReviewService.heldGids(bucket, reviews, settings);
        if (heldGids.contains(gid)) {
            /* 将灰区候选置为待人工审核，已下载或正在下载的健康版本不回退。 */
            markReviewRequired(requested, reviews);
            log.info("🧑‍⚖️ GID {} 存在需人工确认的匹配（审核起点 {} 分，自动门槛 {} 分），等待人工审核",
                    gid, settings.reviewScore(), settings.autoMatchScore());
            return false;
        }
        List<EhGalleriesEntity> available = bucket.stream()
                .filter(item -> !heldGids.contains(item.getGid()))
                .toList();
        List<EhGalleriesEntity> component = componentContaining(available, gid, reviews, settings);
        if (component.isEmpty()) component = List.of(requested);

        // 已有同作品版本正在下载时优先沿用，避免并发父流程重复下载。
        EhGalleriesEntity active = component.stream()
                .filter(item -> !gid.equals(item.getGid()))
                .filter(item -> hasStatus(item, DownloadStatus.DOWNLOADING))
                .max(Comparator.comparingLong(EhGalleriesEntity::getGid))
                .orElse(null);
        if (active != null) {
            /* 统一更新组内首选关系、匹配证据和当前认领状态。 */
            applyDecision(component, active, null, settings);
            /* 记录当前画廊被已有首选替代的原因，并保留其重复版本关系。 */
            markRejectedCandidate(requested, active, settings);
            log.info("🔒 GID {} 与正在下载的 GID {} 匹配，本次不重复派发", gid, active.getGid());
            return false;
        }

        List<EhGalleriesEntity> eligible = component.stream()
                .filter(item -> gid.equals(item.getGid()) || isHealthyOrPending(item))
                .toList();
        EhGalleriesEntity preferred = dedupeReviewService.choosePreferred(
                eligible.isEmpty() ? component : eligible, reviews);
        if (!gid.equals(preferred.getGid())) {
            applyDecision(component, preferred, null, settings);
            markRejectedCandidate(requested, preferred, settings);
            log.info("🔁 GID {} 匹配已有/更优版本 GID {}，跳过下载", gid, preferred.getGid());
            return false;
        }

        applyDecision(component, preferred, gid, settings);
        log.info("✅ GID {} 已原子认领为候选组首选下载版本", gid);
        return true;
    }

    /**
     * 任务结束后重新选择健康的首选版本，优先保留正在下载或已完成的记录。
     * */
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
        DedupeSettings settings = dedupeReviewService.settingsSnapshot();
        for (String key : keys) {
            List<DedupeReviewEntity> reviews = dedupeReviewService.findByCandidateKey(key);
            List<EhGalleriesEntity> bucket = selectByCandidateKey(key);
            Set<Long> heldGids = dedupeReviewService.heldGids(bucket, reviews, settings);
            List<EhGalleriesEntity> available = bucket.stream()
                    .filter(item -> !heldGids.contains(item.getGid()))
                    .toList();
            for (List<EhGalleriesEntity> component : dedupeReviewService.cluster(available, reviews, settings)) {
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
                applyDecision(component, preferred, null, settings);
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

    /**
     * 按稳定顺序读取一个候选组，供分组与首选决策使用。
     * */
    private List<EhGalleriesEntity> selectByCandidateKey(String candidateKey) {
        QueryWrapper<EhGalleriesEntity> query = new QueryWrapper<>();
        query.eq("candidate_key", candidateKey).orderByAsc("gid");
        return galleriesMapper.selectList(query);
    }

    /**
     * 结合自动匹配和人工结论，找出目标画廊所属的同作品分组。
     * */
    private List<EhGalleriesEntity> componentContaining(List<EhGalleriesEntity> bucket, Long gid,
                                                        List<DedupeReviewEntity> reviews, DedupeSettings settings) {
        return dedupeReviewService.cluster(bucket, reviews, settings).stream()
                .filter(group -> group.stream().anyMatch(item -> gid.equals(item.getGid())))
                .findFirst()
                .orElse(List.of());
    }

    private void applyDecision(List<EhGalleriesEntity> component, EhGalleriesEntity preferred,
                               Long claimedGid, DedupeSettings settings) {
        for (EhGalleriesEntity member : component) {
            boolean isPreferred = preferred.getGid().equals(member.getGid());
            GalleryDeduplication.MatchResult match = isPreferred
                    ? new GalleryDeduplication.MatchResult(100, "候选组首选版本")
                    : GalleryDeduplication.match(preferred, member, settings);
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

    private void markRejectedCandidate(EhGalleriesEntity requested, EhGalleriesEntity preferred, DedupeSettings settings) {
        GalleryDeduplication.MatchResult match = GalleryDeduplication.match(preferred, requested, settings);
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

    /**
     * 只更新指定画廊的处理状态。
     * */
    @Override
    public void updateGalleryStatus(Long gid, String status) {
        EhGalleriesEntity updateEntity = new EhGalleriesEntity();
        updateEntity.setGid(gid);
        updateEntity.setDownloadStatus(status);
        galleriesMapper.updateById(updateEntity);
        log.info("📝 状态更新完成, GID: {}, 新状态: {}", gid, status);
    }

    /**
     * 保存一次入库确认的状态、次数、时间和候选原因，并限制说明长度。
     * */
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

    /**
     * 读取需要重新下载或补偿入库的记录，供批量重试流程使用。
     * */
    @Override
    public List<EhGalleriesEntity> getFailedGalleries() {
        QueryWrapper<EhGalleriesEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("download_status", DownloadStatus.DOWNLOAD_FAILED.getValue(),
                DownloadStatus.DOWNLOADED.getValue(), DownloadStatus.KOMGA_IMPORT_FAILED.getValue(),
                DownloadStatus.PARTIAL.getValue());
        return galleriesMapper.selectList(queryWrapper);
    }

    /**
     * 读取单条画廊的最新信息，供后续步骤使用。
     * */
    @Override
    public EhGalleriesEntity getGalleryById(Long gid) {
        return galleriesMapper.selectById(gid);
    }

    /**
     * 批量读取指定画廊，空范围直接返回，避免无条件查询。
     * */
    @Override
    public List<EhGalleriesEntity> getGalleriesByIds(List<Long> gids) {
        if (gids == null || gids.isEmpty()) return Collections.emptyList();
        return galleriesMapper.selectBatchIds(gids);
    }

    /**
     * 把当前运行配置作为活动结果交给工作流，使重放时仍使用历史中记录的设置。
     * */
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

    /**
     * 保存源站估算的归档大小，空参数不覆盖已有值。
     * */
    @Override
    public void updateGallerySize(Long gid, Double sizeMb) {
        if (gid == null || sizeMb == null) return;
        EhGalleriesEntity updateEntity = new EhGalleriesEntity();
        updateEntity.setGid(gid);
        updateEntity.setFileSizeMb(sizeMb);
        galleriesMapper.updateById(updateEntity);
        log.info("💾 大小更新完成, GID: {}, 预估大小: {} MB", gid, sizeMb);
    }

    /**
     * 保存有效的生成摘要，空正文不覆盖已有简介。
     * */
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
