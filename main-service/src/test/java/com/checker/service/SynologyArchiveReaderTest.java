package com.checker.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SynologyArchiveReaderTest {
    @Test
    void seriesResolutionUsesExplicitGidAndNeverChoosesAmbiguousArchives() throws Exception {
        assertEquals("[561583] actual.cbz", SynologyArchiveReader.selectSeriesArchive(
                "legacy title", 561583L, List.of("[561583] actual.cbz", "[5615830] wrong.cbz")).orElseThrow());
        assertEquals("legacy title.cbz", SynologyArchiveReader.selectSeriesArchive(
                "legacy title", 561583L, List.of("legacy title.cbz")).orElseThrow());
        assertTrue(SynologyArchiveReader.selectSeriesArchive("[99] wrong.cbz", 561583L,
                List.of("[99] wrong.cbz")).isEmpty());
        assertThrows(java.io.IOException.class, () -> SynologyArchiveReader.selectSeriesArchive(
                "[561583] a.cbz", 561583L, List.of("[561583] a.cbz", "[561583] b.zip")));
    }
    @Test
    void resolvesHistoricalArchiveByStableGidWhenStoredTitleChanged() {
        String requested = "[647361] [chaccu] Walpurgis no Inmu Aido Iris _ new title[Chinese].zip";

        String resolved = SynologyArchiveReader.selectGidArchive(requested, List.of(
                "[123] unrelated.cbz",
                "[647361] [chaccu] Walpurgis no Inmu Aido Iris - old title.cbz"))
                .orElseThrow();

        assertEquals("[647361] [chaccu] Walpurgis no Inmu Aido Iris - old title.cbz", resolved);
    }

    @Test
    void doesNotMatchLongerGidOrUnsupportedFiles() {
        assertTrue(SynologyArchiveReader.selectGidArchive("[647361] expected.zip", List.of(
                "[6473610] wrong.cbz", "[647361] metadata.xml", ".[647361] file.cbz.uploading")).isEmpty());
    }

    @Test
    void detectsSameGidAcrossZipAndCbzNames() {
        assertTrue(SynologyArchiveReader.isArchiveForSameGid(
                "[3627694] current title.zip", "[3627694] historical title.cbz"));
        assertTrue(!SynologyArchiveReader.isArchiveForSameGid(
                "[3627694] current title.zip", "[36276940] other.cbz"));
    }
}
