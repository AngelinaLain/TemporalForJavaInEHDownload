package com.checker.common;

import com.checker.entity.EhGalleriesEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GalleryDeduplicationTest {

    @Test
    void groupsChineseTranslationsByOriginalTitleAndCoreTags() {
        EhGalleriesEntity simplified = gallery(101L, "[汉化组 A] 中文标题", "作品原題", 4.2D, 28);
        EhGalleriesEntity traditional = gallery(102L, "[繁中] 另一個中文標題", "作品原題", 4.5D, 28);

        GalleryDeduplication.populateIdentity(simplified);
        GalleryDeduplication.populateIdentity(traditional);

        assertTrue(GalleryDeduplication.isIdentifiable(simplified));
        assertEquals(simplified.getDedupeKey(), traditional.getDedupeKey());
        assertEquals(simplified.getCandidateKey(), traditional.getCandidateKey());
        assertEquals(100, simplified.getDedupeConfidence());
        assertEquals(GalleryDeduplication.ALGORITHM_VERSION, simplified.getDedupeAlgorithmVersion());
        assertEquals(traditional, GalleryDeduplication.choosePreferred(List.of(simplified, traditional)));
    }

    @Test
    void keepsDifferentOriginalWorksSeparate() {
        EhGalleriesEntity first = gallery(101L, "中文标题", "原作 A", 4.2D, 28);
        EhGalleriesEntity second = gallery(102L, "中文标题", "原作 B", 4.9D, 40);

        GalleryDeduplication.populateIdentity(first);
        GalleryDeduplication.populateIdentity(second);

        assertNotEquals(first.getDedupeKey(), second.getDedupeKey());
    }

    @Test
    void toleratesIncompleteCharacterTagsThroughMultiSignalScoring() {
        EhGalleriesEntity complete = gallery(201L, "[汉化] 标题", "同一原作", 4.1D, 30);
        EhGalleriesEntity incomplete = gallery(202L, "标题", "同一原作", 4.2D, 29);
        incomplete.setTags(List.of("parody:original-work", "artist:author"));

        GalleryDeduplication.populateIdentity(complete);
        GalleryDeduplication.populateIdentity(incomplete);

        assertEquals(complete.getCandidateKey(), incomplete.getCandidateKey());
        assertEquals(complete.getDedupeKey(), incomplete.getDedupeKey(),
                "严格键不应被 character 标签数量波动影响");
        GalleryDeduplication.MatchResult result = GalleryDeduplication.match(complete, incomplete);
        assertTrue(result.score() >= GalleryDeduplication.AUTO_MATCH_THRESHOLD, result.reason());
    }

    @Test
    void toleratesMissingCreatorTagsWhenOtherSignalsAreStrong() {
        EhGalleriesEntity complete = gallery(211L, "[汉化] 标题", "同一原作", 4.1D, 30);
        EhGalleriesEntity missingCreator = gallery(212L, "标题", "同一原作", 4.2D, 29);
        missingCreator.setTags(List.of("parody:original-work", "character:heroine"));

        GalleryDeduplication.populateIdentity(complete);
        GalleryDeduplication.populateIdentity(missingCreator);

        assertEquals(complete.getCandidateKey(), missingCreator.getCandidateKey());
        assertNotEquals(complete.getDedupeKey(), missingCreator.getDedupeKey());
        GalleryDeduplication.MatchResult result = GalleryDeduplication.match(complete, missingCreator);
        assertTrue(result.score() >= GalleryDeduplication.AUTO_MATCH_THRESHOLD, result.reason());
    }

    @Test
    void rejectsSameTitleWhenCreatorsConflict() {
        EhGalleriesEntity first = gallery(301L, "同名作品", "同名原作", 4.0D, 20);
        EhGalleriesEntity second = gallery(302L, "同名作品", "同名原作", 4.0D, 20);
        second.setTags(List.of("parody:other-work", "character:other", "artist:different-author"));

        GalleryDeduplication.populateIdentity(first);
        GalleryDeduplication.populateIdentity(second);

        assertEquals(first.getCandidateKey(), second.getCandidateKey());
        GalleryDeduplication.MatchResult result = GalleryDeduplication.match(first, second);
        assertFalse(result.score() >= GalleryDeduplication.AUTO_MATCH_THRESHOLD, result.reason());
        assertTrue(result.reason().contains("冲突"));
    }

    @Test
    void clustersOnlyCandidatesThatReachAutomaticThreshold() {
        EhGalleriesEntity first = gallery(401L, "版本一", "聚类作品", 4.1D, 25);
        EhGalleriesEntity second = gallery(402L, "版本二", "聚类作品", 4.2D, 25);
        EhGalleriesEntity conflict = gallery(403L, "版本三", "聚类作品", 4.9D, 25);
        conflict.setTags(List.of("parody:different", "character:different", "group:other-group"));
        List.of(first, second, conflict).forEach(GalleryDeduplication::populateIdentity);

        List<List<EhGalleriesEntity>> groups = GalleryDeduplication.clusterCandidates(
                List.of(first, second, conflict));

        assertEquals(2, groups.size());
        assertTrue(groups.stream().anyMatch(group -> group.size() == 2));
    }

    @Test
    void allowsPersistentHumanDecisionToOverrideAutomaticScore() {
        EhGalleriesEntity first = gallery(501L, "同名作品", "同名原作", 4.0D, 20);
        EhGalleriesEntity conflict = gallery(502L, "同名作品", "同名原作", 4.1D, 20);
        conflict.setTags(List.of("parody:other", "character:other", "artist:other"));
        List.of(first, conflict).forEach(GalleryDeduplication::populateIdentity);

        assertFalse(GalleryDeduplication.isAutomaticMatch(first, conflict));
        assertEquals(1, GalleryDeduplication.clusterCandidates(
                List.of(first, conflict), (left, right) -> true).size());
        assertEquals(2, GalleryDeduplication.clusterCandidates(
                List.of(first, conflict), (left, right) -> false).size());
    }

    @Test
    void sameStrictKeyDoesNotReplaceMissingEvidenceOrHideConflicts() {
        EhGalleriesEntity first = gallery(601L, "版本一", "同名作品", 4D, 20);
        EhGalleriesEntity second = gallery(602L, "版本二", "同名作品", 4D, 20);
        first.setTags(List.of());
        second.setTags(List.of());
        List.of(first, second).forEach(GalleryDeduplication::populateIdentity);

        assertEquals(first.getDedupeKey(), second.getDedupeKey());
        assertEquals(75, GalleryDeduplication.match(first, second).score());
        assertFalse(GalleryDeduplication.isAutomaticMatch(first, second));

        first.setTags(List.of("parody:first-work"));
        second.setTags(List.of("parody:other-work"));
        List.of(first, second).forEach(GalleryDeduplication::populateIdentity);
        assertEquals(first.getDedupeKey(), second.getDedupeKey());
        assertEquals(65, GalleryDeduplication.match(first, second).score());
        assertTrue(GalleryDeduplication.match(first, second).reason().contains("原作冲突"));
        assertFalse(GalleryDeduplication.isAutomaticMatch(first, second));
    }

    @Test
    void ignoresStaleStrictKeyWhenCurrentCreatorEvidenceConflicts() {
        EhGalleriesEntity first = gallery(611L, "版本一", "同名作品", 4D, 20);
        EhGalleriesEntity second = gallery(612L, "版本二", "同名作品", 4D, 20);
        List.of(first, second).forEach(GalleryDeduplication::populateIdentity);
        second.setTags(List.of("artist:different-author", "parody:other-work"));

        assertEquals(first.getDedupeKey(), second.getDedupeKey());
        assertFalse(GalleryDeduplication.isAutomaticMatch(first, second));
        assertTrue(GalleryDeduplication.match(first, second).reason().contains("作者/社团冲突"));
    }

    @Test
    void doesNotMergeDifferentChaptersEvenWhenBracketRemovalCreatesSameKey() {
        EhGalleriesEntity first = gallery(621L, "版本一", "A Shared Story (Chapter 1)", 4D, 20);
        EhGalleriesEntity second = gallery(622L, "版本二", "A Shared Story (Chapter 2)", 4D, 20);
        List.of(first, second).forEach(GalleryDeduplication::populateIdentity);
        DedupeSettings loose = new DedupeSettings(40, 60, 70, 18, 3, 70, 3);

        assertEquals(first.getDedupeKey(), second.getDedupeKey());
        assertTrue(GalleryDeduplication.match(first, second).score() >= 85);
        assertTrue(GalleryDeduplication.match(first, second).reason().contains("编号不同"));
        assertFalse(GalleryDeduplication.isAutomaticMatch(first, second));
        assertFalse(GalleryDeduplication.isAutomaticMatch(first, second, loose));
    }

    @Test
    void doesNotMergeStandaloneChapterWithMuchLongerCollection() {
        EhGalleriesEntity chapter = gallery(631L, "单篇", "同一原作", 4D, 20);
        EhGalleriesEntity collection = gallery(632L, "合集", "同一原作", 4D, 80);
        List.of(chapter, collection).forEach(GalleryDeduplication::populateIdentity);

        assertTrue(GalleryDeduplication.match(chapter, collection).score() >= 85);
        assertTrue(GalleryDeduplication.match(chapter, collection).reason().contains("单篇与合集"));
        assertFalse(GalleryDeduplication.isAutomaticMatch(chapter, collection));
        assertEquals(2, GalleryDeduplication.clusterCandidates(List.of(chapter, collection)).size());
    }

    @Test
    void appliesConfiguredAutomaticAndTitleThresholdsWithoutChangingIdentity() {
        EhGalleriesEntity first = gallery(641L, "版本一", "LongSharedTitleForMatchingA", 4D, 20);
        EhGalleriesEntity second = gallery(642L, "版本二", "LongSharedTitleForMatchingB", 4D, 20);
        List.of(first, second).forEach(GalleryDeduplication::populateIdentity);
        String originalKey = first.getDedupeKey();
        DedupeSettings strictTitle = new DedupeSettings(65, 85, 100, 18, 3, 70, 3);

        assertTrue(GalleryDeduplication.isAutomaticMatch(first, second));
        assertFalse(GalleryDeduplication.isAutomaticMatch(first, second, strictTitle));
        assertEquals(originalKey, first.getDedupeKey());

        second.setOriginalTitle(first.getOriginalTitle());
        first.setTags(List.of());
        second.setTags(List.of());
        assertFalse(GalleryDeduplication.isAutomaticMatch(first, second));
        assertTrue(GalleryDeduplication.isAutomaticMatch(first, second,
                new DedupeSettings(40, 70, 88, 18, 3, 70, 3)));
    }

    @Test
    void toleratesNullTagsAndKeepsCompleteLinkGrouping() {
        EhGalleriesEntity first = gallery(651L, "版本一", "同一原作", 4D, 20);
        EhGalleriesEntity second = gallery(652L, "版本二", "同一原作", 4D, 20);
        second.setTags(Arrays.asList(null, "artist:author", "parody:original-work"));
        List.of(first, second).forEach(GalleryDeduplication::populateIdentity);
        assertTrue(GalleryDeduplication.isAutomaticMatch(first, second));

        EhGalleriesEntity third = gallery(653L, "版本三", "同一原作", 4D, 20);
        assertEquals(2, GalleryDeduplication.clusterCandidates(List.of(third, second, first),
                (left, right) -> Math.abs(left.getGid() - right.getGid()) == 1).size());
    }

    private EhGalleriesEntity gallery(Long gid, String title, String originalTitle, Double rating, Integer pageCount) {
        EhGalleriesEntity gallery = new EhGalleriesEntity();
        gallery.setGid(gid);
        gallery.setTitle(title);
        gallery.setOriginalTitle(originalTitle);
        gallery.setRating(rating);
        gallery.setPageCount(pageCount);
        gallery.setTags(List.of("parody:original-work", "character:heroine", "artist:author"));
        return gallery;
    }
}
