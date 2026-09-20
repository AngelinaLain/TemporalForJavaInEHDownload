package com.checker.common;

import com.checker.entity.EhGalleriesEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GallerySeriesMatchingTest {
    @Test
    void stripsEhCreditsTranslationAndVolumeMarkers() {
        assertEquals("ふたりの秘密", GallerySeriesMatching.baseTitle(
                "(C105) [Circle Name] ふたりの秘密 Vol. 2 [中国翻訳]"));
        assertEquals("ふたりの秘密", GallerySeriesMatching.baseTitle(
                "[Circle Name] ふたりの秘密 第3話 [Chinese]"));
    }

    @Test
    void combinesTitleCoverAndMetadataSignals() {
        EhGalleriesEntity first = gallery("[A] Star Story Vol.1 [Chinese]", List.of("artist:a", "parody:x"));
        EhGalleriesEntity second = gallery("(C100) [A] Star Story 2", List.of("artist:a", "parody:x"));
        GallerySeriesMatching.SeriesMatch result = GallerySeriesMatching.score(
                first, "0000000000000000", second, "0000000000000000");
        assertTrue(result.score() >= 90);
        assertEquals(100, result.coverSimilarity());
    }

    private EhGalleriesEntity gallery(String title, List<String> tags) {
        EhGalleriesEntity gallery = new EhGalleriesEntity();
        gallery.setTitle(title);
        gallery.setTags(tags);
        return gallery;
    }
}
