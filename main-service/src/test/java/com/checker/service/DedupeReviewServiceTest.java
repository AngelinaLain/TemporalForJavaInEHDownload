package com.checker.service;

import com.checker.common.GalleryDeduplication;
import com.checker.common.DedupeSettings;
import com.checker.entity.DedupeReviewEntity;
import com.checker.entity.EhGalleriesEntity;
import com.checker.dto.GalleryVisualMatch;
import com.checker.mapper.DedupeReviewMapper;
import com.checker.mapper.EhGalleriesMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DedupeReviewServiceTest {
    private final DedupeSettingsService settingsService = mock(DedupeSettingsService.class);
    private final DedupeReviewService service = new DedupeReviewService(null, null, null, settingsService);

    DedupeReviewServiceTest() {
        when(settingsService.current()).thenReturn(DedupeSettings.defaults());
    }

    @Test
    void pendingReviewAlsoHoldsManuallyMatchedMembers() {
        DedupeReviewEntity pending = review(1L, 2L, DedupeReviewService.PENDING, null);
        DedupeReviewEntity confirmed = review(2L, 3L, DedupeReviewService.MATCH, 2L);

        assertEquals(Set.of(1L, 2L, 3L), service.heldGids(List.of(pending, confirmed)));
    }

    @Test
    void pendingReviewHoldsAutomaticallyMatchedThirdVersion() {
        EhGalleriesEntity first = gallery(1L, "版本一", "同一作品", "artist:a");
        EhGalleriesEntity automaticPeer = gallery(2L, "版本二", "同一作品", "artist:a");
        EhGalleriesEntity grayPeer = gallery(3L, "版本三", "同一作品", "artist:b");
        DedupeReviewEntity pending = review(1L, 3L, DedupeReviewService.PENDING, null);

        assertEquals(Set.of(1L, 2L, 3L), service.heldGids(
                List.of(first, automaticPeer, grayPeer), List.of(pending)));
    }

    @Test
    void humanMatchAndDifferentOverrideAlgorithm() {
        EhGalleriesEntity first = gallery(10L, "作品", "作品", "artist:a");
        EhGalleriesEntity second = gallery(11L, "作品", "作品", "artist:b");
        assertTrue(GalleryDeduplication.match(first, second).score()
                < GalleryDeduplication.AUTO_MATCH_THRESHOLD);

        assertEquals(1, service.cluster(List.of(first, second),
                List.of(review(10L, 11L, DedupeReviewService.MATCH, 10L))).size());
        assertEquals(2, service.cluster(List.of(first, second),
                List.of(review(10L, 11L, DedupeReviewService.DIFFERENT, null))).size());
        assertEquals(2, service.cluster(List.of(first, second),
                List.of(review(10L, 11L, DedupeReviewService.VARIANT, null))).size());
    }

    @Test
    void latestHumanPreferredVersionWins() {
        EhGalleriesEntity first = gallery(20L, "作品", "作品", "artist:a");
        EhGalleriesEntity second = gallery(21L, "作品", "作品", "artist:a");
        DedupeReviewEntity decision = review(20L, 21L, DedupeReviewService.MATCH, 20L);

        assertEquals(20L, service.choosePreferred(List.of(first, second), List.of(decision)).getGid());
    }

    @Test
    void configuredThresholdsChangeNewReviewsAndClusteringUsingTheSameSnapshot() {
        DedupeReviewMapper reviews = mock(DedupeReviewMapper.class);
        VisualFingerprintService visual = mock(VisualFingerprintService.class);
        when(reviews.selectList(any())).thenReturn(List.of());
        when(visual.matchAndPersist(anyLong(), anyLong(), any())).thenReturn(emptyVisual());
        DedupeReviewService configured = new DedupeReviewService(reviews, null, visual, settingsService);
        EhGalleriesEntity first = gallery(1L, "版本一", "同名作品", "artist:a");
        EhGalleriesEntity second = gallery(2L, "版本二", "同名作品", "artist:a");
        first.setTags(List.of()); second.setTags(List.of());
        List<EhGalleriesEntity> bucket = List.of(first, second);
        DedupeSettings defaults = DedupeSettings.defaults();
        DedupeSettings reviewAboveScore = new DedupeSettings(80, 90, 88, 18, 3, 70, 3);
        DedupeSettings lowerAuto = new DedupeSettings(65, 70, 88, 18, 3, 70, 3);

        configured.ensureGrayZoneReviews("bucket", bucket, defaults);
        assertEquals(1, pendingInsertCount(reviews));
        assertEquals(2, configured.cluster(bucket, List.of(), defaults).size());
        clearInvocations(reviews, visual);
        configured.ensureGrayZoneReviews("bucket", bucket, reviewAboveScore);
        configured.ensureGrayZoneReviews("bucket", bucket, lowerAuto);
        assertEquals(0, pendingInsertCount(reviews));
        verifyNoInteractions(visual);
        assertEquals(1, configured.cluster(bucket, List.of(), lowerAuto).size());
        verifyNoInteractions(settingsService);
    }

    @Test
    void existingPendingAndResolvedReviewsNeverRecalculateVisualEvidenceDuringDownloadClaims() {
        DedupeReviewMapper reviews = mock(DedupeReviewMapper.class);
        VisualFingerprintService visual = mock(VisualFingerprintService.class);
        DedupeReviewService configured = new DedupeReviewService(reviews, null, visual, settingsService);
        EhGalleriesEntity first = gallery(1L, "版本一", "同名作品", "artist:a");
        EhGalleriesEntity second = gallery(2L, "版本二", "同名作品", "artist:a");
        for (String decision : List.of(DedupeReviewService.PENDING, DedupeReviewService.MATCH,
                DedupeReviewService.DIFFERENT, DedupeReviewService.VARIANT)) {
            when(reviews.selectList(any())).thenReturn(List.of(review(1L, 2L, decision, 1L)));
            assertEquals(0, configured.ensureGrayZoneReviews("bucket", List.of(first, second), DedupeSettings.defaults()));
        }
        assertEquals(0, pendingInsertCount(reviews));
        verifyNoInteractions(visual, settingsService);
        verify(reviews, never()).update(any(), any());
    }

    @Test
    void highScoreChapterAndCollectionConflictsStillCreateReviews() {
        DedupeReviewMapper reviews = mock(DedupeReviewMapper.class);
        when(reviews.selectList(any())).thenReturn(List.of());
        DedupeReviewService configured = new DedupeReviewService(reviews, null, null, settingsService);
        EhGalleriesEntity first = gallery(1L, "版本一", "Shared Story (Chapter 1)", "artist:a");
        EhGalleriesEntity nextChapter = gallery(2L, "版本二", "Shared Story (Chapter 2)", "artist:a");
        EhGalleriesEntity collection = gallery(3L, "版本三", "Shared Story (Chapter 1)", "artist:a");
        collection.setPageCount(80);
        DedupeSettings lowThreshold = new DedupeSettings(40, 60, 70, 18, 3, 70, 3);
        assertTrue(GalleryDeduplication.match(first, nextChapter, lowThreshold).score() >= 85);
        assertTrue(GalleryDeduplication.match(first, collection, lowThreshold).score() >= 85);
        configured.ensureGrayZoneReviews("bucket", List.of(first, nextChapter), lowThreshold);
        configured.ensureGrayZoneReviews("bucket", List.of(first, collection), lowThreshold);
        assertEquals(2, pendingInsertCount(reviews));
    }

    @Test
    void explicitHumanDecisionsRemainAuthoritativeAcrossThresholdChangesAndConflictGuards() {
        EhGalleriesEntity first = gallery(1L, "版本一", "Shared Story (Chapter 1)", "artist:a");
        EhGalleriesEntity second = gallery(2L, "版本二", "Shared Story (Chapter 2)", "artist:a");
        DedupeSettings strict = new DedupeSettings(90, 100, 100, 0, 16, 100, 30);
        assertEquals(2, service.cluster(List.of(first, second), List.of(), strict).size());
        assertEquals(1, service.cluster(List.of(first, second),
                List.of(review(1L, 2L, DedupeReviewService.MATCH, 1L)), strict).size());
        second.setOriginalTitle(first.getOriginalTitle());
        DedupeSettings loose = new DedupeSettings(40, 60, 70, 24, 2, 30, 1);
        for (String decision : List.of(DedupeReviewService.DIFFERENT, DedupeReviewService.VARIANT,
                DedupeReviewService.PENDING)) {
            assertEquals(2, service.cluster(List.of(first, second), List.of(review(1L, 2L, decision, null)), loose).size());
        }
        verifyNoInteractions(settingsService);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void refreshingWeakEvidenceClearsAnOutdatedRecommendationWithoutChangingHumanFields() {
        DedupeReviewMapper reviews = mock(DedupeReviewMapper.class);
        EhGalleriesMapper galleries = mock(EhGalleriesMapper.class);
        VisualFingerprintService visual = mock(VisualFingerprintService.class);
        DedupeReviewEntity pending = review(1L, 2L, DedupeReviewService.PENDING, null);
        pending.setId(11L); pending.setCandidateKey("bucket"); pending.setRecommendedGid(1L);
        when(reviews.selectById(11L)).thenReturn(pending);
        when(reviews.selectByIdForUpdate(11L)).thenReturn(pending);
        when(visual.matchAndPersist(1L, 2L, DedupeSettings.defaults())).thenReturn(emptyVisual());
        DedupeReviewService configured = new DedupeReviewService(reviews, galleries, visual, settingsService);
        assertSame(pending, configured.refreshVisualEvidence(11L));
        ArgumentCaptor<UpdateWrapper<DedupeReviewEntity>> update = ArgumentCaptor.forClass((Class) UpdateWrapper.class);
        verify(reviews).update(isNull(), update.capture());
        String assignments = update.getValue().getSqlSet();
        var recommendation = Pattern.compile("(?:^|,)recommended_gid=#\\{ew\\.paramNameValuePairs\\.(\\w+)}").matcher(assignments);
        assertTrue(recommendation.find(), assignments);
        assertNull(update.getValue().getParamNameValuePairs().get(recommendation.group(1)));
        assertFalse(assignments.contains("preferred_gid="));
        assertFalse(assignments.contains("decision="));
        verify(settingsService).current();
        verify(galleries).ensureDedupeLock("bucket");
        verify(galleries).lockDedupeCandidate("bucket");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void refreshingResolvedReviewPreservesTheChosenVersionAndRecommendation() {
        DedupeReviewMapper reviews = mock(DedupeReviewMapper.class);
        EhGalleriesMapper galleries = mock(EhGalleriesMapper.class);
        VisualFingerprintService visual = mock(VisualFingerprintService.class);
        DedupeReviewEntity confirmed = review(1L, 2L, DedupeReviewService.MATCH, 2L);
        confirmed.setId(11L); confirmed.setCandidateKey("bucket"); confirmed.setRecommendedGid(2L);
        when(reviews.selectById(11L)).thenReturn(confirmed);
        when(reviews.selectByIdForUpdate(11L)).thenReturn(confirmed);
        when(visual.matchAndPersist(1L, 2L, DedupeSettings.defaults())).thenReturn(emptyVisual());
        DedupeReviewService configured = new DedupeReviewService(reviews, galleries, visual, settingsService);
        configured.refreshVisualEvidence(11L);
        ArgumentCaptor<UpdateWrapper<DedupeReviewEntity>> update = ArgumentCaptor.forClass((Class) UpdateWrapper.class);
        verify(reviews).update(isNull(), update.capture());
        String assignments = update.getValue().getSqlSet();
        assertFalse(Pattern.compile("(?:^|,)recommended_gid=").matcher(assignments).find());
        assertFalse(assignments.contains("preferred_gid="));
        assertFalse(assignments.contains("decision="));
        assertEquals(2L, confirmed.getPreferredGid());
    }

    private long pendingInsertCount(DedupeReviewMapper reviews) {
        return mockingDetails(reviews).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("insertPending")).count();
    }

    private GalleryVisualMatch emptyVisual() {
        return GalleryVisualMatch.builder().similarity(0).matchedPages(0).sampleCoverage(0).orderConsistency(0)
                .qualityDelta(0).algorithmVersion(1).reason("一侧尚无可用视觉指纹").build();
    }

    private DedupeReviewEntity review(Long left, Long right, String decision, Long preferred) {
        DedupeReviewEntity review = new DedupeReviewEntity();
        review.setLeftGid(left);
        review.setRightGid(right);
        review.setDecision(decision);
        review.setPreferredGid(preferred);
        review.setMatchScore(70);
        return review;
    }

    private EhGalleriesEntity gallery(Long gid, String title, String originalTitle, String creator) {
        EhGalleriesEntity gallery = new EhGalleriesEntity();
        gallery.setGid(gid);
        gallery.setTitle(title);
        gallery.setOriginalTitle(originalTitle);
        gallery.setRating(4D);
        gallery.setPageCount(20);
        gallery.setTags(List.of("parody:work", creator));
        GalleryDeduplication.populateIdentity(gallery);
        return gallery;
    }
}
