package com.checker.service;

import com.checker.entity.EhGalleriesEntity;
import com.checker.dto.GalleryPageFingerprint;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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

    @Test
    void coverSimilarityUsesCenterHashWhenTranslationOverlayChangesEdges() {
        GalleryPageFingerprint source = fingerprint("0000000000000000", "aaaaaaaaaaaaaaaa");
        GalleryPageFingerprint candidate = fingerprint("ffffffffffffffff", "aaaaaaaaaaaaaaaa");

        assertEquals(100, ArchiveSyncService.coverSimilarity(source, candidate));
    }

    @Test
    void considersSameGidArchivedWhenOnlyExtensionChanged() {
        EhGalleriesEntity gallery = gallery(197775L, "Dark Make up", null);
        gallery.setFilename("[197775] Dark Make up [Chinese].zip");

        Set<Long> archived = ArchiveSyncService.findArchivedGroups(List.of(gallery),
                List.of("[197775] Dark Make up [Chinese].cbz"));

        assertEquals(Set.of(197775L), archived);
    }

    @Test
    void considersPreferredGalleryArchivedWhenCandidateVersionExists() {
        EhGalleriesEntity preferred = gallery(4166897L, "Preferred", null);
        preferred.setFilename("[4166897] Preferred.cbz");
        EhGalleriesEntity candidate = gallery(4166800L, "Candidate", null);
        candidate.setDuplicateOfGid(4166897L);
        candidate.setFilename("[4166800] Historical name.zip");

        Set<Long> archived = ArchiveSyncService.findArchivedGroups(List.of(preferred, candidate),
                List.of("[4166800] Historical name.cbz"));

        assertEquals(Set.of(4166897L), archived);
    }

    @Test
    void extractsEhCoverFromMetadataCssAndThumbnailFallbacks() {
        var document = Jsoup.parse("""
                <meta property='og:image' content='/cover.jpg'>
                <div id='gd1'><div style=\"background: url('//img.example/front.jpg')\"></div></div>
                <div id='gdt'><img data-src='' src='https://img.example/page1.jpg'></div>
                """, "https://e-hentai.org/g/1/token/");

        assertEquals(List.of("https://e-hentai.org/cover.jpg", "https://img.example/front.jpg",
                        "https://img.example/page1.jpg"),
                ArchiveSyncService.extractSourceCoverUrls(document));
    }

    private GalleryPageFingerprint fingerprint(String full, String center) {
        return GalleryPageFingerprint.builder().perceptualHash(full).centerHash(center).build();
    }

    private EhGalleriesEntity gallery(long gid, String title, String originalTitle) {
        EhGalleriesEntity gallery = new EhGalleriesEntity();
        gallery.setGid(gid);
        gallery.setTitle(title);
        gallery.setOriginalTitle(originalTitle);
        return gallery;
    }
}
