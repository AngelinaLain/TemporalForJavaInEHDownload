package com.checker.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.checker.common.DownloadStatus;
import com.checker.common.GalleryDeduplication;
import com.checker.entity.DedupeReviewEntity;
import com.checker.entity.EhGalleriesEntity;
import com.checker.dto.GalleryVisualMatch;
import com.checker.mapper.DedupeReviewMapper;
import com.checker.mapper.EhGalleriesMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 整理重复画廊候选、生成灰区审核，并把人工选择安全地写回版本关系。 */
@Service
public class DedupeReviewService {
    public static final String PENDING = "PENDING";
    public static final String MATCH = "MATCH";
    public static final String DIFFERENT = "DIFFERENT";
    public static final String VARIANT = "VARIANT";

    private final DedupeReviewMapper reviewMapper;
    private final EhGalleriesMapper galleriesMapper;
    private final VisualFingerprintService visualFingerprintService;

    public DedupeReviewService(DedupeReviewMapper reviewMapper, EhGalleriesMapper galleriesMapper,
                               VisualFingerprintService visualFingerprintService) {
        this.reviewMapper = reviewMapper;
        this.galleriesMapper = galleriesMapper;
        this.visualFingerprintService = visualFingerprintService;
    }

    /** 分页查询人工审核记录，并附加候选画廊及当前视觉证据。 */
    public IPage<DedupeReviewEntity> page(int page, int size, String decision) {
        QueryWrapper<DedupeReviewEntity> query = new QueryWrapper<>();
        if (decision != null && !decision.isBlank() && !"ALL".equalsIgnoreCase(decision)) {
            query.eq("decision", normalizeDecision(decision));
        }
        query.orderByAsc("decision").orderByDesc("created_at").orderByDesc("id");
        return reviewMapper.selectPage(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)), query);
    }

    /** 统计仍待人工处置的记录数量。 */
    public long countPending() {
        QueryWrapper<DedupeReviewEntity> query = new QueryWrapper<>();
        query.eq("decision", PENDING);
        return reviewMapper.selectCount(query);
    }

    /** 读取审核记录，不存在时停止后续处置。 */
    public DedupeReviewEntity require(Long id) {
        if (id == null) throw new IllegalArgumentException("审核记录不存在");
        DedupeReviewEntity review = reviewMapper.selectById(id);
        if (review == null) throw new IllegalArgumentException("审核记录不存在");
        return review;
    }
    /** 查找同一候选组的审核记录，避免重复建立同组审核。 */
    public List<DedupeReviewEntity> findByCandidateKey(String candidateKey) {
        if (candidateKey == null || candidateKey.isBlank()) return List.of();
        QueryWrapper<DedupeReviewEntity> query = new QueryWrapper<>();
        query.eq("candidate_key", candidateKey).orderByAsc("id");
        return reviewMapper.selectList(query);
    }

    /** 为同一候选桶中处于灰区的画廊对创建幂等审核记录。 */
    public int ensureGrayZoneReviews(String candidateKey, List<EhGalleriesEntity> bucket) {
        if (candidateKey == null || bucket == null || bucket.size() < 2) return 0;
        Map<PairKey, DedupeReviewEntity> existing = index(findByCandidateKey(candidateKey));
        int created = 0;
        for (int leftIndex = 0; leftIndex < bucket.size(); leftIndex++) {
            for (int rightIndex = leftIndex + 1; rightIndex < bucket.size(); rightIndex++) {
                EhGalleriesEntity left = bucket.get(leftIndex);
                EhGalleriesEntity right = bucket.get(rightIndex);
                PairKey pair = PairKey.of(left.getGid(), right.getGid());
                if (existing.containsKey(pair)) {
                    refreshVisualEvidence(existing.get(pair));
                    continue;
                }
                GalleryDeduplication.MatchResult match = GalleryDeduplication.match(left, right);
                if (match.score() < GalleryDeduplication.REVIEW_MATCH_THRESHOLD
                        || match.score() >= GalleryDeduplication.AUTO_MATCH_THRESHOLD) {
                    continue;
                }
                GalleryVisualMatch visual = visualFingerprintService == null ? null
                        : visualFingerprintService.matchAndPersist(pair.left(), pair.right());
                Long recommended = visual != null && visual.getRecommendedGid() != null
                        ? visual.getRecommendedGid()
                        : GalleryDeduplication.choosePreferred(List.of(left, right)).getGid();
                created += reviewMapper.insertPending(candidateKey, pair.left(), pair.right(),
                        match.score(), match.reason(),
                        visual == null ? null : visual.getSimilarity(),
                        visual == null ? null : visual.getMatchedPages(),
                        visual == null ? null : visual.getSampleCoverage(),
                        visual == null ? null : visual.getOrderConsistency(),
                        visual == null ? null : visual.getRecommendedGid(),
                        visual == null ? null : visual.getQualityDelta(),
                        visual == null ? null : visual.getReason(),
                        visual == null ? null : visual.getAlgorithmVersion(), recommended);
            }
        }
        return created;
    }

    /** 画廊指纹变化后刷新它涉及的审核证据。 */
    public void refreshVisualEvidenceForGid(Long gid) {
        if (gid == null) return;
        QueryWrapper<DedupeReviewEntity> query = new QueryWrapper<>();
        query.and(wrapper -> wrapper.eq("left_gid", gid).or().eq("right_gid", gid));
        reviewMapper.selectList(query).forEach(this::refreshVisualEvidence);
    }

    /** 重新计算指定审核的视觉证据，不替代已经记录的人工决定。 */
    private void refreshVisualEvidence(DedupeReviewEntity review) {
        if (review == null || visualFingerprintService == null) return;
        GalleryVisualMatch visual = visualFingerprintService.matchAndPersist(review.getLeftGid(), review.getRightGid());
        UpdateWrapper<DedupeReviewEntity> update = new UpdateWrapper<>();
        update.eq("id", review.getId())
                .set("visual_similarity", visual.getSimilarity())
                .set("visual_matched_pages", visual.getMatchedPages())
                .set("visual_sample_coverage", visual.getSampleCoverage())
                .set("visual_order_consistency", visual.getOrderConsistency())
                .set("visual_recommended_gid", visual.getRecommendedGid())
                .set("visual_quality_delta", visual.getQualityDelta())
                .set("visual_reason", visual.getReason())
                .set("visual_algorithm_version", visual.getAlgorithmVersion());
        if (PENDING.equals(review.getDecision()) && visual.getRecommendedGid() != null) {
            update.set("recommended_gid", visual.getRecommendedGid());
        }
        reviewMapper.update(null, update);
    }

    /** 收集仍被人工审核占用的画廊，避免自动选优提前处理这些记录。 */
    public Set<Long> heldGids(List<DedupeReviewEntity> reviews) {
        Set<Long> held = new HashSet<>();
        for (DedupeReviewEntity review : reviews) {
            if (PENDING.equals(review.getDecision())) {
                held.add(review.getLeftGid());
                held.add(review.getRightGid());
            }
        }
        boolean changed;
        do {
            changed = false;
            for (DedupeReviewEntity review : reviews) {
                if (!MATCH.equals(review.getDecision())) continue;
                if (held.contains(review.getLeftGid()) && held.add(review.getRightGid())) changed = true;
                if (held.contains(review.getRightGid()) && held.add(review.getLeftGid())) changed = true;
            }
        } while (changed);
        return held;
    }

    /**
     * 在待审核端点基础上继续扩展自动同组成员，避免 A-C 待审时与 A 自动匹配的 B 抢先下载。
     */
    public Set<Long> heldGids(List<EhGalleriesEntity> bucket, List<DedupeReviewEntity> reviews) {
        Set<Long> held = heldGids(reviews);
        Map<Long, EhGalleriesEntity> byGid = new HashMap<>();
        bucket.forEach(item -> byGid.put(item.getGid(), item));
        Map<PairKey, DedupeReviewEntity> decisions = index(reviews);
        boolean changed;
        do {
            changed = false;
            List<Long> heldSnapshot = List.copyOf(held);
            for (EhGalleriesEntity candidate : bucket) {
                if (held.contains(candidate.getGid())) continue;
                for (Long heldGid : heldSnapshot) {
                    EhGalleriesEntity heldGallery = byGid.get(heldGid);
                    if (heldGallery != null && effectiveMatch(heldGallery, candidate, decisions)) {
                        held.add(candidate.getGid());
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);
        return held;
    }

    /** 根据候选证据和已确认关系整理需要一起比较的版本组。 */
    public List<List<EhGalleriesEntity>> cluster(
            List<EhGalleriesEntity> bucket, List<DedupeReviewEntity> reviews) {
        Map<PairKey, DedupeReviewEntity> decisions = index(reviews);
        return GalleryDeduplication.clusterCandidates(bucket,
                (left, right) -> effectiveMatch(left, right, decisions));
    }

    /** 在候选组中确定首选版本，并保留当前健康或正在处理的记录。 */
    public EhGalleriesEntity choosePreferred(
            List<EhGalleriesEntity> candidates, List<DedupeReviewEntity> reviews) {
        Set<Long> candidateGids = new HashSet<>();
        candidates.forEach(item -> candidateGids.add(item.getGid()));
        Long manualPreferred = reviews.stream()
                .filter(review -> MATCH.equals(review.getDecision()) && review.getPreferredGid() != null)
                .filter(review -> candidateGids.contains(review.getLeftGid())
                        && candidateGids.contains(review.getRightGid())
                        && candidateGids.contains(review.getPreferredGid()))
                .max(Comparator.comparing(this::decisionTime))
                .map(DedupeReviewEntity::getPreferredGid)
                .orElse(null);
        if (manualPreferred != null) {
            return candidates.stream()
                    .filter(item -> manualPreferred.equals(item.getGid()))
                    .findFirst()
                    .orElseThrow();
        }
        return GalleryDeduplication.choosePreferred(candidates);
    }

    /** 锁定候选组后保存人工结论，重算版本关系并返回需要继续处理的画廊。 */
    @Transactional
    public ResolutionOutcome resolve(Long reviewId, String requestedDecision,
                                     Long preferredGid, String reviewedBy) {
        String decision = normalizeDecision(requestedDecision);
        if (!MATCH.equals(decision) && !DIFFERENT.equals(decision) && !VARIANT.equals(decision)) {
            throw new IllegalArgumentException("审核结论只支持 MATCH、DIFFERENT 或 VARIANT");
        }
        DedupeReviewEntity snapshot = reviewMapper.selectById(reviewId);
        if (snapshot == null) throw new IllegalArgumentException("审核记录不存在");
        // 与下载认领保持相同锁顺序：先候选桶、后审核记录，避免交叉等待。
        lockCandidateKey(snapshot.getCandidateKey());
        DedupeReviewEntity review = reviewMapper.selectByIdForUpdate(reviewId);
        if (review == null) throw new IllegalArgumentException("审核记录不存在");
        if (MATCH.equals(decision)
                && !review.getLeftGid().equals(preferredGid)
                && !review.getRightGid().equals(preferredGid)) {
            throw new IllegalArgumentException("首选 GID 必须是当前审核记录中的一个版本");
        }

        review.setDecision(decision);
        review.setPreferredGid(MATCH.equals(decision) ? preferredGid : null);
        review.setReviewedBy(reviewedBy);
        review.setReviewedAt(new Date());
        reviewMapper.updateById(review);

        Set<Long> affected = new HashSet<>(Set.of(review.getLeftGid(), review.getRightGid()));
        List<Long> dispatchGids = recomputeCandidate(review.getCandidateKey(), affected);
        return new ResolutionOutcome(review.getId(), decision, dispatchGids);
    }

    /** 按最新人工结论和候选证据重算组内首选及重复关系。 */
    private List<Long> recomputeCandidate(String candidateKey, Set<Long> affected) {
        List<EhGalleriesEntity> bucket = selectBucket(candidateKey);
        List<DedupeReviewEntity> reviews = findByCandidateKey(candidateKey);
        expandByManualMatches(affected, reviews);
        Set<Long> held = heldGids(bucket, reviews);

        for (EhGalleriesEntity gallery : bucket) {
            if (held.contains(gallery.getGid()) && !isHealthyOrActive(gallery)) {
                setStatus(gallery.getGid(), DownloadStatus.REVIEW_REQUIRED);
            }
        }

        List<Long> dispatch = new ArrayList<>();
        List<EhGalleriesEntity> available = bucket.stream()
                .filter(item -> !held.contains(item.getGid()))
                .toList();
        for (List<EhGalleriesEntity> group : cluster(available, reviews)) {
            EhGalleriesEntity preferred = choosePreferred(group, reviews);
            boolean affectedGroup = group.stream().anyMatch(item -> affected.contains(item.getGid()));
            for (EhGalleriesEntity member : group) {
                boolean isPreferred = preferred.getGid().equals(member.getGid());
                GalleryDeduplication.MatchResult match = isPreferred
                        ? new GalleryDeduplication.MatchResult(100, "人工审核后的候选组首选版本")
                        : GalleryDeduplication.match(preferred, member);
                UpdateWrapper<EhGalleriesEntity> update = new UpdateWrapper<>();
                update.eq("gid", member.getGid())
                        .set("duplicate_of_gid", isPreferred ? null : preferred.getGid())
                        .set("dedupe_match_score", match.score())
                        .set("dedupe_match_reason", match.reason())
                        .set("dedupe_algorithm_version", GalleryDeduplication.ALGORITHM_VERSION);
                if (isPreferred && (hasStatus(member, DownloadStatus.REVIEW_REQUIRED)
                        || hasStatus(member, DownloadStatus.IGNORED))) {
                    update.set("download_status", DownloadStatus.PENDING.getValue());
                } else if (!isPreferred && !isHealthyOrActive(member)) {
                    update.set("download_status", DownloadStatus.IGNORED.getValue());
                }
                galleriesMapper.update(null, update);
            }
            if (affectedGroup && !isHealthyOrActive(preferred)
                    && (hasStatus(preferred, DownloadStatus.REVIEW_REQUIRED)
                    || hasStatus(preferred, DownloadStatus.PENDING)
                    || hasStatus(preferred, DownloadStatus.IGNORED))) {
                dispatch.add(preferred.getGid());
            }
        }
        return dispatch.stream().distinct().toList();
    }

    /** 把人工确认相同的画廊纳入候选组，避免分组边界丢掉已确认关系。 */
    private void expandByManualMatches(Set<Long> affected, List<DedupeReviewEntity> reviews) {
        boolean changed;
        do {
            changed = false;
            for (DedupeReviewEntity review : reviews) {
                if (!MATCH.equals(review.getDecision())) continue;
                if (affected.contains(review.getLeftGid()) && affected.add(review.getRightGid())) changed = true;
                if (affected.contains(review.getRightGid()) && affected.add(review.getLeftGid())) changed = true;
            }
        } while (changed);
    }

    private Map<PairKey, DedupeReviewEntity> index(List<DedupeReviewEntity> reviews) {
        Map<PairKey, DedupeReviewEntity> result = new HashMap<>();
        for (DedupeReviewEntity review : reviews) {
            result.put(PairKey.of(review.getLeftGid(), review.getRightGid()), review);
        }
        return result;
    }

    /** 把人工决定和自动视觉证据合并为本次使用的匹配结论。 */
    private boolean effectiveMatch(EhGalleriesEntity left, EhGalleriesEntity right,
                                   Map<PairKey, DedupeReviewEntity> decisions) {
        DedupeReviewEntity review = decisions.get(PairKey.of(left.getGid(), right.getGid()));
        if (review != null) {
            if (MATCH.equals(review.getDecision())) return true;
            if (DIFFERENT.equals(review.getDecision()) || VARIANT.equals(review.getDecision())
                    || PENDING.equals(review.getDecision())) return false;
        }
        return GalleryDeduplication.isAutomaticMatch(left, right);
    }

    private List<EhGalleriesEntity> selectBucket(String candidateKey) {
        QueryWrapper<EhGalleriesEntity> query = new QueryWrapper<>();
        query.eq("candidate_key", candidateKey).orderByAsc("gid");
        return galleriesMapper.selectList(query);
    }

    /** 锁定同一候选组的数据库锁桶，串行处理同时发生的选优和审核。 */
    private void lockCandidateKey(String candidateKey) {
        galleriesMapper.ensureDedupeLock(candidateKey);
        galleriesMapper.lockDedupeCandidate(candidateKey);
    }

    private void setStatus(Long gid, DownloadStatus status) {
        UpdateWrapper<EhGalleriesEntity> update = new UpdateWrapper<>();
        update.eq("gid", gid).set("download_status", status.getValue());
        galleriesMapper.update(null, update);
    }

    private boolean isHealthyOrActive(EhGalleriesEntity gallery) {
        return hasStatus(gallery, DownloadStatus.IMPORTED)
                || hasStatus(gallery, DownloadStatus.DOWNLOADED)
                || hasStatus(gallery, DownloadStatus.WAITING_KOMGA)
                || hasStatus(gallery, DownloadStatus.KOMGA_IMPORT_FAILED)
                || hasStatus(gallery, DownloadStatus.DOWNLOADING);
    }

    private static boolean hasStatus(EhGalleriesEntity gallery, DownloadStatus status) {
        return gallery != null && (status.getValue().equals(gallery.getDownloadStatus())
                || status.name().equals(gallery.getDownloadStatus()));
    }

    private Date decisionTime(DedupeReviewEntity review) {
        if (review.getReviewedAt() != null) return review.getReviewedAt();
        if (review.getUpdatedAt() != null) return review.getUpdatedAt();
        return new Date(0);
    }

    private String normalizeDecision(String decision) {
        return decision == null ? "" : decision.trim().toUpperCase();
    }

    /** 固定较小 GID 在左，使同一对画廊不会因传参顺序不同形成两份索引。 */
    public record PairKey(Long left, Long right) {
        public static PairKey of(Long first, Long second) {
            if (first == null || second == null) throw new IllegalArgumentException("GID 不能为空");
            return first <= second ? new PairKey(first, second) : new PairKey(second, first);
        }
    }

    /** 返回人工结论和需要继续下载处理的首选画廊，供调用方接续工作流。 */
    public record ResolutionOutcome(Long reviewId, String decision, List<Long> dispatchGids) {
    }
}
