package com.checker.service.rss;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.checker.dao.catalog.EhGalleriesMapper;
import com.checker.dao.dedupe.DedupeReviewMapper;
import com.checker.dedupe.DedupeSettings;
import com.checker.entity.EhGalleriesEntity;
import com.checker.model.dataobject.dedupe.DedupeReviewEntity;
import com.checker.service.dedupe.DedupeSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 RSS 归位前的匹配决策：优先复用去重关系与人工复核，其次在候选组内自动匹配，
 * 没有匹配时才把订阅画廊自身作为归位对象。
 */
class RssPlacementResolverTest {

    private final EhGalleriesMapper galleries = mock(EhGalleriesMapper.class);
    private final DedupeReviewMapper reviews = mock(DedupeReviewMapper.class);
    private final DedupeSettingsService settings = mock(DedupeSettingsService.class);
    private final RssPlacementResolver resolver = new RssPlacementResolver(galleries, reviews, settings);

    @BeforeEach
    void defaults() {
        when(reviews.selectList(any())).thenReturn(List.of());
        when(settings.current()).thenReturn(DedupeSettings.defaults());
    }

    @Test
    void missingGalleryWaitsForDetailFetch() {
        when(galleries.selectById(7L)).thenReturn(null);
        var decision = resolver.resolve(7L);
        assertFalse(decision.ready());
        assertEquals("WAITING_IMPORT", decision.status());
        assertEquals(7L, decision.placementGid());
    }

    @Test
    void duplicateChainResolvesToPreferredGallery() {
        when(galleries.selectById(7L)).thenReturn(gallery(7L, "SKIPPED", 8L, null));
        when(galleries.selectById(8L)).thenReturn(gallery(8L, "IMPORTED", 9L, null));
        when(galleries.selectById(9L)).thenReturn(gallery(9L, "IMPORTED", null, null));

        var decision = resolver.resolve(7L);
        assertTrue(decision.ready());
        assertEquals(RssPlacementResolver.MatchSource.DUPLICATE, decision.source());
        assertEquals(9L, decision.placementGid());
    }

    @Test
    void pendingReviewBlocksPlacementUntilHumanDecision() {
        when(galleries.selectById(7L)).thenReturn(gallery(7L, "REVIEW_REQUIRED", null, "candidate"));
        DedupeReviewEntity review = new DedupeReviewEntity();
        review.setId(31L);
        review.setLeftGid(7L);
        review.setRightGid(8L);
        review.setMatchScore(72);
        when(reviews.selectList(any())).thenReturn(List.of(review));

        var decision = resolver.resolve(7L);
        assertFalse(decision.ready());
        assertEquals("WAITING_REVIEW", decision.status());
        assertEquals(31L, decision.reviewId());
        assertTrue(decision.reason().contains("人工确认"));
    }

    @Test
    void reviewRequiredStatusWaitsEvenWithoutVisibleReviewRow() {
        when(galleries.selectById(7L)).thenReturn(gallery(7L, "REVIEW_REQUIRED", null, "candidate"));
        var decision = resolver.resolve(7L);
        assertFalse(decision.ready());
        assertEquals("WAITING_REVIEW", decision.status());
    }

    @Test
    void ignoredGalleryIsTerminalWithoutArchive() {
        when(galleries.selectById(7L)).thenReturn(gallery(7L, "IGNORED", null, "candidate"));
        var decision = resolver.resolve(7L);
        assertFalse(decision.ready());
        assertEquals("SKIPPED_IGNORED", decision.status());
        assertTrue(decision.terminal());
    }

    @Test
    void automaticCandidateMatchPlacesExistingImportedGallery() {
        EhGalleriesEntity rssGallery = gallery(7L, "IMPORTED", null, "same-candidate");
        rssGallery.setOriginalTitle("Same Work");
        rssGallery.setPageCount(10);
        rssGallery.setTags(List.of("artist:alpha"));
        rssGallery.setFilename("rss.cbz");
        EhGalleriesEntity existing = gallery(8L, "IMPORTED", null, "same-candidate");
        existing.setOriginalTitle("Same Work");
        existing.setPageCount(10);
        existing.setTags(List.of("artist:alpha"));
        existing.setFilename("existing.cbz");
        when(galleries.selectById(7L)).thenReturn(rssGallery);
        when(galleries.selectList(any())).thenReturn(List.of(rssGallery, existing));

        var decision = resolver.resolve(7L);
        assertTrue(decision.ready());
        assertEquals(RssPlacementResolver.MatchSource.AUTO_MATCH, decision.source());
        assertEquals(8L, decision.placementGid());
    }

    @Test
    void unmatchedGalleryPlacesItself() {
        EhGalleriesEntity rssGallery = gallery(7L, "IMPORTED", null, "candidate");
        rssGallery.setOriginalTitle("Only Work");
        rssGallery.setFilename("rss.cbz");
        when(galleries.selectById(7L)).thenReturn(rssGallery);
        when(galleries.selectList(any())).thenReturn(List.of(rssGallery));

        var decision = resolver.resolve(7L);
        assertTrue(decision.ready());
        assertEquals(RssPlacementResolver.MatchSource.SELF, decision.source());
        assertEquals(7L, decision.placementGid());
    }

    private static EhGalleriesEntity gallery(long gid, String status, Long duplicateOf, String candidateKey) {
        EhGalleriesEntity gallery = new EhGalleriesEntity();
        gallery.setGid(gid);
        gallery.setDownloadStatus(status);
        gallery.setDuplicateOfGid(duplicateOf);
        gallery.setCandidateKey(candidateKey);
        gallery.setTitle("Gallery " + gid);
        return gallery;
    }
}
