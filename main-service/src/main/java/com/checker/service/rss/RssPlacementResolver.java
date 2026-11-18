package com.checker.service.rss;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.checker.dao.catalog.EhGalleriesMapper;
import com.checker.dao.dedupe.DedupeReviewMapper;
import com.checker.dedupe.DedupeSettings;
import com.checker.dedupe.GalleryDeduplication;
import com.checker.entity.EhGalleriesEntity;
import com.checker.model.dataobject.dedupe.DedupeReviewEntity;
import com.checker.service.dedupe.DedupeReviewService;
import com.checker.service.dedupe.DedupeSettingsService;
import com.checker.shared.DownloadStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RSS 归入合集前的匹配决策。
 *
 * <p>订阅画廊到达归位阶段时，先复用去重模块已有的匹配结果：重复关系（含视觉证据与人工结论）
 * 指向的既有画廊、仍待人工确认的审核记录，以及同一候选组内达到自动合并门槛的既有画廊。
 * 只有确定归位对象后才由 {@link RssCollectionService} 建立合集成员关系并整理归档。</p>
 */
@Service
public class RssPlacementResolver {

    /** 匹配来源，写入 rss_collection_task.match_source 供页面与统计区分。 */
    public enum MatchSource {
        /** 订阅画廊自身就是要归入合集的画廊 */
        SELF,
        /** 去重关系（自动匹配或人工确认）指向已有画廊 */
        DUPLICATE,
        /** 同候选组中已有画廊达到自动匹配门槛 */
        AUTO_MATCH
    }

    /**
     * 归位决策。status 为空表示可以继续归位；否则是任务应停留的状态：
     * WAITING_IMPORT（等待入库）、WAITING_REVIEW（等待人工复核）、SKIPPED_IGNORED（画廊已忽略，无归档可归位）。
     */
    public record Decision(long rssGid, long placementGid, MatchSource source, String reason,
                           String status, Long reviewId) {

        /** 是否可以继续建立合集关系并整理归档。 */
        public boolean ready() {
            return status == null;
        }

        /** 是否是无需重试的终态（已有归属、已忽略等）。 */
        public boolean terminal() {
            return status != null && status.startsWith("SKIPPED");
        }

        static Decision blocked(long rssGid, long placementGid, MatchSource source,
                                String status, String reason, Long reviewId) {
            return new Decision(rssGid, placementGid, source, reason, status, reviewId);
        }
    }

    private static final int MAX_DUPLICATE_HOPS = 8;

    private final EhGalleriesMapper galleries;
    private final DedupeReviewMapper reviews;
    private final DedupeSettingsService dedupeSettings;

    public RssPlacementResolver(EhGalleriesMapper galleries, DedupeReviewMapper reviews,
                                DedupeSettingsService dedupeSettings) {
        this.galleries = galleries;
        this.reviews = reviews;
        this.dedupeSettings = dedupeSettings;
    }

    /**
     * 解析订阅画廊的归位对象：优先沿去重关系找到既有画廊，其次等待人工复核，最后在候选组内自动匹配。
     * 任何一步无法确认时都返回可重试或终态，不在归位阶段修改去重结论。
     */
    public Decision resolve(long rssGid) {
        EhGalleriesEntity gallery = galleries.selectById(rssGid);
        if (gallery == null) {
            return Decision.blocked(rssGid, rssGid, MatchSource.SELF, "WAITING_IMPORT", "画廊记录尚未生成，等待抓取详情", null);
        }

        Long duplicated = duplicateRoot(gallery);
        if (duplicated != null && duplicated != rssGid) {
            EhGalleriesEntity target = galleries.selectById(duplicated);
            if (target == null) {
                return Decision.blocked(rssGid, rssGid, MatchSource.SELF, "WAITING_IMPORT",
                        "去重关系指向的既有画廊记录不存在，等待人工核对", null);
            }
            String reason = "去重关系匹配到既有画廊 GID " + duplicated;
            return new Decision(rssGid, duplicated, MatchSource.DUPLICATE,
                    appendReason(reason, gallery.getDedupeMatchReason()), null, null);
        }

        DedupeReviewEntity pending = pendingReview(rssGid);
        if (pending != null) {
            return Decision.blocked(rssGid, rssGid, MatchSource.SELF, "WAITING_REVIEW",
                    "等待人工确认匹配（审核 #" + pending.getId() + "，匹配分 " + pending.getMatchScore() + "）",
                    pending.getId());
        }
        if (hasStatus(gallery, DownloadStatus.REVIEW_REQUIRED)) {
            return Decision.blocked(rssGid, rssGid, MatchSource.SELF, "WAITING_REVIEW",
                    "画廊处于人工复核队列，确认后再归入合集", null);
        }
        if (hasStatus(gallery, DownloadStatus.IGNORED)) {
            return Decision.blocked(rssGid, rssGid, MatchSource.SELF, "SKIPPED_IGNORED",
                    "画廊已被忽略且没有可归位的归档，保持原样", null);
        }

        EhGalleriesEntity matched = autoMatch(gallery);
        if (matched != null && !matched.getGid().equals(rssGid)) {
            return new Decision(rssGid, matched.getGid(), MatchSource.AUTO_MATCH,
                    "候选组自动匹配既有画廊 GID " + matched.getGid(), null, null);
        }

        return new Decision(rssGid, rssGid, MatchSource.SELF, "未匹配到既有画廊，按订阅画廊归入合集", null, null);
    }

    /**
     * 沿 duplicate_of_gid 找到首选画廊；循环或过长的关系视为不可信，回退到当前记录。
     */
    private Long duplicateRoot(EhGalleriesEntity gallery) {
        Long current = gallery.getDuplicateOfGid();
        if (current == null) return null;
        Set<Long> visited = new HashSet<>();
        visited.add(gallery.getGid());
        for (int hop = 0; hop < MAX_DUPLICATE_HOPS && current != null; hop++) {
            if (!visited.add(current)) return null;
            EhGalleriesEntity next = galleries.selectById(current);
            if (next == null) return null;
            if (next.getDuplicateOfGid() == null) return current;
            current = next.getDuplicateOfGid();
        }
        return null;
    }

    /**
     * 查找与当前画廊相关且尚未处理的复核记录；存在时先等待人工结论，不自动归位。
     */
    private DedupeReviewEntity pendingReview(long gid) {
        QueryWrapper<DedupeReviewEntity> query = new QueryWrapper<>();
        query.eq("decision", DedupeReviewService.PENDING)
                .and(wrapper -> wrapper.eq("left_gid", gid).or().eq("right_gid", gid))
                .orderByDesc("match_score")
                .last("LIMIT 1");
        List<DedupeReviewEntity> rows = reviews.selectList(query);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 在同一候选组中寻找已入库、尚未成为重复版本且达到自动匹配门槛的既有画廊。
     * 该检查只读，不写入去重结论，避免与批量重建的候选关系互相覆盖。
     */
    private EhGalleriesEntity autoMatch(EhGalleriesEntity gallery) {
        String candidateKey = gallery.getCandidateKey();
        if (candidateKey == null || candidateKey.isBlank()) return null;
        DedupeSettings settings = dedupeSettings.current();
        QueryWrapper<EhGalleriesEntity> query = new QueryWrapper<>();
        query.eq("candidate_key", candidateKey)
                .isNull("duplicate_of_gid")
                .ne("gid", gallery.getGid());
        List<EhGalleriesEntity> bucket = galleries.selectList(query);
        return bucket.stream()
                .filter(candidate -> candidate.getGid() != null && !candidate.getGid().equals(gallery.getGid()))
                .filter(this::importedWithArchive)
                .filter(candidate -> GalleryDeduplication.isAutomaticMatch(gallery, candidate, settings))
                .max(Comparator.comparingInt(candidate -> GalleryDeduplication.match(gallery, candidate, settings).score()))
                .orElse(null);
    }

    private boolean importedWithArchive(EhGalleriesEntity gallery) {
        return hasStatus(gallery, DownloadStatus.IMPORTED)
                && gallery.getFilename() != null && !gallery.getFilename().isBlank();
    }

    private static boolean hasStatus(EhGalleriesEntity gallery, DownloadStatus status) {
        return gallery != null && (status.name().equals(gallery.getDownloadStatus())
                || status.getValue().equals(gallery.getDownloadStatus()));
    }

    private static String appendReason(String reason, String matchReason) {
        return matchReason == null || matchReason.isBlank() ? reason : reason + "：" + matchReason;
    }
}
