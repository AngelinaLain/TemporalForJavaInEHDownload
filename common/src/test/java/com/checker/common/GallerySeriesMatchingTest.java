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

    @Test
    void recognizesKotohanaNumberedSeriesWithNestedCreditAndDifferentSubtitles() {
        EhGalleriesEntity second = gallery(
                "[OXIDE_Lab (OXIDEENGINE)] Kotohana 2 -Sei Shinkan Shokusou Bounyuu Kaizou- [Chinese]",
                List.of());
        second.setOriginalTitle("[OXIDE_Lab (OXIDEENGINE)] 異花2-聖神官触装乳改造- [中国翻訳] [DL版]");
        EhGalleriesEntity fourth = gallery(
                "[OXIDE_Lab (OXIDEENGINE)] Kotohana 4 -Seishinkan Innyuu Ganrou Kairou- [Chinese]",
                List.of());
        fourth.setOriginalTitle("[OXIDE_Lab (OXIDEENGINE)] 異花4-聖神官淫乳玩弄回牢- [中国翻訳]");

        GallerySeriesMatching.SeriesMatch result = GallerySeriesMatching.score(second, null, fourth, null);

        assertEquals(result.leftBaseTitle(), result.rightBaseTitle());
        assertTrue(List.of("異花", "kotohana").contains(result.leftBaseTitle()));
        assertEquals(100, result.titleSimilarity());
        assertTrue(result.score() >= 90);
    }

    private EhGalleriesEntity gallery(String title, List<String> tags) {
        EhGalleriesEntity gallery = new EhGalleriesEntity();
        gallery.setTitle(title);
        gallery.setTags(tags);
        return gallery;
    }
}
