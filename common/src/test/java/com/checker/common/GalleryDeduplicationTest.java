package com.checker.common;

import com.checker.entity.EhGalleriesEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

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
