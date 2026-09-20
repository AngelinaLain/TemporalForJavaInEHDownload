package com.checker.service;

import com.checker.entity.EhGalleriesEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveSyncServiceTest {
    @Test
    void prefersStableGidBeforeTitleMatching() {
        ArchiveSyncService.MatchResult result = ArchiveSyncService.findCandidates(
                gallery(647361L, "Completely New Database Title", null),
                List.of("[647361] historical title.zip", "Completely New Database Title.cbz"));

        assertEquals("GID", result.type());
        assertEquals(List.of("[647361] historical title.zip"), result.filenames());
    }

    @Test
    void fallsBackToOriginalGalleryTitle() {
        ArchiveSyncService.MatchResult result = ArchiveSyncService.findCandidates(
                gallery(100L, "中文译名", "Walpurgis no Inmu Aido Iris"),
                List.of("[chaccu] Walpurgis no Inmu Aido Iris [Chinese].zip"));

        assertEquals("TITLE", result.type());
        assertEquals(1, result.filenames().size());
    }

    @Test
    void returnsSeveralCloseFuzzyCandidatesForManualReview() {
        ArchiveSyncService.MatchResult result = ArchiveSyncService.findCandidates(
                gallery(200L, "Kotohana Seishinkan Shokushu", null),
                List.of("Kotohana 2 Seishinkan Shokushuu.cbz", "Kotohana 3 Seishinkan Shokushu.zip"));

        assertEquals("FUZZY", result.type());
        assertEquals(2, result.filenames().size());
    }

    @Test
    void usesDistinctiveTitleFragmentWhenOldFilenameContainsOnlyPartOfTitle() {
        ArchiveSyncService.MatchResult result = ArchiveSyncService.findCandidates(
                gallery(201L, "[circle] The Very Long Walpurgis Chronicle Special Edition", null),
                List.of("old release - Walpurgis - chinese.cbz", "totally unrelated.cbz"));

        assertEquals("FUZZY", result.type());
        assertEquals(List.of("old release - Walpurgis - chinese.cbz"), result.filenames());
    }

    @Test
    void reportsMissingWhenNoStrategyMatches() {
        ArchiveSyncService.MatchResult result = ArchiveSyncService.findCandidates(
                gallery(300L, "Unique Gallery Name", null), List.of("unrelated archive.cbz"));

        assertEquals("MISSING", result.type());
        assertTrue(result.filenames().isEmpty());
    }

    private EhGalleriesEntity gallery(long gid, String title, String originalTitle) {
        EhGalleriesEntity gallery = new EhGalleriesEntity();
        gallery.setGid(gid);
        gallery.setTitle(title);
        gallery.setOriginalTitle(originalTitle);
        return gallery;
    }
}
