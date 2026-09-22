package com.checker.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynologyArchiveReaderTest {
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
