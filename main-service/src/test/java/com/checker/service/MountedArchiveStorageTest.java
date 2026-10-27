package com.checker.service;

import com.checker.config.EhNetworkConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MountedArchiveStorageTest {
    @TempDir
    Path tempDir;

    @Test
    void writesReadsAndDeletesHistoricalNameByGid() throws Exception {
        EhNetworkConfig config = new EhNetworkConfig();
        config.getArchiveStorage().setMountPath(tempDir.toString());
        MountedArchiveStorage storage = new MountedArchiveStorage(config);
        Path local = tempDir.resolve("source.cbz");
        Files.writeString(local, "archive", StandardCharsets.UTF_8);
        AtomicLong progress = new AtomicLong();
        Path stale = tempDir.resolve("OXIDE_Lab/[3627694] historical title.zip");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "archive", StandardCharsets.UTF_8);

        storage.upload(local, "OXIDE_Lab", "[3627694] database title.cbz", progress::set);

        Path target = tempDir.resolve("OXIDE_Lab/[3627694] database title.cbz");
        assertTrue(Files.isRegularFile(target));
        assertFalse(Files.exists(stale));
        assertEquals(Files.size(local), progress.get());
        assertEquals("archive", storage.read("OXIDE_Lab", "[3627694] changed title.cbz",
                input -> new String(input.readAllBytes(), StandardCharsets.UTF_8)));

        storage.delete("OXIDE_Lab", "[3627694] changed title.cbz");
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(target.resolveSibling(".[3627694] database title.cbz.uploading")));
    }

    @Test
    void rejectsDirectoryTraversal() {
        EhNetworkConfig config = new EhNetworkConfig();
        config.getArchiveStorage().setMountPath(tempDir.toString());
        MountedArchiveStorage storage = new MountedArchiveStorage(config);

        assertThrows(IllegalArgumentException.class,
                () -> storage.delete("../outside", "[1] file.cbz"));
    }

    @Test
    void missingDirectoryErrorExplainsWhatWasNotFound() {
        EhNetworkConfig config = new EhNetworkConfig();
        config.getArchiveStorage().setMountPath(tempDir.toString());
        MountedArchiveStorage storage = new MountedArchiveStorage(config);

        NoSuchFileException failure = assertThrows(NoSuchFileException.class,
                () -> storage.read("missing-series", "[42] title.cbz", input -> null));

        assertTrue(failure.getMessage().contains("归档目录不存在"));
        assertTrue(failure.getMessage().contains("missing-series"));
    }

    @Test
    void previewFindsMovedSeriesAndRootArchivesByTheStableGid() throws Exception {
        MountedArchiveStorage storage = previewStorage();
        Path moved = Files.createDirectories(tempDir.resolve("new-series/volume")).resolve("[42] new title.cbz");
        Files.writeString(moved, "archive");
        Path legacy = tempDir.resolve("[43] legacy.zip");
        Files.writeString(legacy, "archive");

        assertEquals(moved.toRealPath(), storage.findReadableArchive("old-series", "[42] old title.zip", 42L).orElseThrow());
        assertEquals(legacy.toRealPath(), storage.findReadableArchive("missing-series", "[43] old name.cbz", 43L).orElseThrow());
    }

    @Test
    void previewCanLocateAnArchiveWithoutADatabaseFilenameAndDoesNotUseNearbyGids() throws Exception {
        MountedArchiveStorage storage = previewStorage();
        Path archive = Files.createDirectories(tempDir.resolve("series")).resolve("[42] saved.cbz");
        Files.writeString(archive, "archive");
        Files.writeString(tempDir.resolve("[420] unrelated.cbz"), "other");

        assertEquals(archive.toRealPath(), storage.findReadableArchive(null, null, 42L).orElseThrow());
        assertTrue(storage.findReadableArchive("missing", "", 4L).isEmpty());
    }

    @Test
    void previewNeverPicksAnArbitraryDuplicateAndExplicitRecordedPathRemainsAuthoritative() throws Exception {
        MountedArchiveStorage storage = previewStorage();
        Path first = Files.createDirectories(tempDir.resolve("first")).resolve("[42] one.cbz");
        Files.writeString(first, "first");
        Path second = Files.createDirectories(tempDir.resolve("second")).resolve("[42] two.zip");
        Files.writeString(second, "second");

        var failure = assertThrows(MountedArchiveStorage.PreviewLookupException.class,
                () -> storage.findReadableArchive("stale", "[42] renamed.cbz", 42L));
        assertEquals("AMBIGUOUS", failure.code());
        assertTrue(failure.getMessage().contains("多个"));
        assertEquals(first.toRealPath(), storage.findReadableArchive("first", "[42] one.cbz", 42L).orElseThrow());
    }

    @Test
    void previewIndexIsReusedUntilInvalidatedAndRefreshesAFileMovedAfterIndexing() throws Exception {
        MountedArchiveStorage storage = previewStorage();
        assertTrue(storage.findReadableArchive("missing", null, 42L).isEmpty());
        Path first = tempDir.resolve("[42] new.cbz");
        Files.writeString(first, "archive");
        assertTrue(storage.findReadableArchive("missing", null, 42L).isEmpty(), "negative lookups reuse the catalog instead of rescanning");
        storage.invalidatePreviewIndex();
        assertEquals(first.toRealPath(), storage.findReadableArchive("missing", null, 42L).orElseThrow());
        Path moved = Files.createDirectories(tempDir.resolve("moved")).resolve("[42] moved.cbz");
        Files.move(first, moved);
        assertEquals(moved.toRealPath(), storage.findReadableArchive("missing", null, 42L).orElseThrow());
    }

    @Test
    void previewRejectsTraversalAndMismatchedIdentityAndExplainsMissingMount() throws Exception {
        MountedArchiveStorage storage = previewStorage();
        assertThrows(IllegalArgumentException.class, () -> storage.findReadableArchive("../outside", "[42] a.cbz", 42L));
        assertThrows(IllegalArgumentException.class, () -> storage.findReadableArchive("", "../a.cbz", 42L));
        assertThrows(IllegalArgumentException.class, () -> storage.findReadableArchive("", "[43] a.cbz", 42L));
        assertThrows(IllegalArgumentException.class, () -> storage.findReadableArchive("", "a.cbz", 0L));
        var missing = assertThrows(MountedArchiveStorage.PreviewLookupException.class,
                () -> new MountedArchiveStorage(new EhNetworkConfig()).findReadableArchive("", null, 42L));
        assertEquals("NOT_MOUNTED", missing.code());
    }

    private MountedArchiveStorage previewStorage() {
        EhNetworkConfig config = new EhNetworkConfig();
        config.getArchiveStorage().setMountPath(tempDir.toString());
        return new MountedArchiveStorage(config);
    }
}
