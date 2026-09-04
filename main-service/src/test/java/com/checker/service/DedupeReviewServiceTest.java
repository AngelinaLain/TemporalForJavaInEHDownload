package com.checker.service;

import com.checker.common.GalleryDeduplication;
import com.checker.entity.DedupeReviewEntity;
import com.checker.entity.EhGalleriesEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DedupeReviewServiceTest {
    private final DedupeReviewService service = new DedupeReviewService(null, null, null);

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
