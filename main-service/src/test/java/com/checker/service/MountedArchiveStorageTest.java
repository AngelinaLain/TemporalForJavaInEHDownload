package com.checker.service;

import com.checker.config.EhNetworkConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

        storage.upload(local, "OXIDE_Lab", "[3627694] database title.cbz", progress::set);

        Path target = tempDir.resolve("OXIDE_Lab/[3627694] database title.cbz");
        assertTrue(Files.isRegularFile(target));
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
}
