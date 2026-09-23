package com.checker.service;

import com.checker.clients.KomgaApiClient;
import com.checker.config.EhNetworkConfig;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KomgaSeriesSyncServiceTest {
    @TempDir Path root;
    EhNetworkConfig config;
    EhGalleriesMapper mapper;
    GallerySeriesPlacementService placements;
    SynologyArchiveReader reader;
    SynologyUploadService uploader;
    KomgaApiClient komga;
    MountedArchiveStorage storage;
    EhGalleriesEntity gallery;
    final String filename = "[561583] historical title.cbz";

    @BeforeEach
    void setUp() throws Exception {
        config = new EhNetworkConfig();
        config.getArchiveStorage().setMountPath(root.toString());
        config.getDownload().setTempDir(root.resolve("tmp").toString());
        storage = new MountedArchiveStorage(config);
        reader = spy(new SynologyArchiveReader(config, storage));
        uploader = mock(SynologyUploadService.class);
        doAnswer(call -> {
            storage.upload(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
            return null;
        }).when(uploader).upload(any(Path.class), anyString(), anyString(), any());
        mapper = mock(EhGalleriesMapper.class);
        gallery = new EhGalleriesEntity();
        gallery.setGid(561583L);
        gallery.setFilename("historical title");
        gallery.setTitle("Title");
        when(mapper.selectList(any())).thenReturn(List.of(gallery));
        when(mapper.update(isNull(), any())).thenReturn(1);
        placements = mock(GallerySeriesPlacementService.class);
        when(placements.resolve(561583L)).thenReturn(
                new GallerySeriesPlacementService.SeriesPlacement(1L, "Series", "Series", 1, "1:Series:0"));
        komga = mock(KomgaApiClient.class);
    }

    KomgaSeriesSyncService service() {
        return new KomgaSeriesSyncService(mapper, placements, reader, uploader, komga, config, Runnable::run);
    }

    void archive(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new ZipEntry("001.jpg"));
            out.write(new byte[] {1, 2, 3, 4});
            out.closeEntry();
        }
    }

    @Test
    void recoversLegacyRowWhenOnlyDestinationExistsAndSecondClickDoesNothing() throws Exception {
        Path destination = root.resolve("Series/" + filename);
        archive(destination);
        assertEquals(1, service().start().get("succeeded"));
        assertEquals(filename, gallery.getFilename());
        assertEquals("Series", gallery.getStoragePath());
        assertEquals("1:Series:0", gallery.getSeriesSyncSignature());
        try (ZipFile zip = new ZipFile(destination.toFile())) {
            assertNotNull(zip.getEntry("ComicInfo.xml"));
            assertArrayEquals(new byte[] {1, 2, 3, 4}, zip.getInputStream(zip.getEntry("001.jpg")).readAllBytes());
            assertTrue(new String(zip.getInputStream(zip.getEntry("ComicInfo.xml")).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).contains("<Series>Series</Series>"));
        }
        assertEquals(0, service().start().get("total"));
        verify(uploader, times(1)).upload(any(), anyString(), anyString(), any());
        verify(reader, never()).deleteExact(anyString(), anyString());
    }

    @Test
    void persistsDestinationBeforeDeletingSourceAndKeepsCleanupAcrossRestart() throws Exception {
        archive(root.resolve(filename));
        doThrow(new IOException("NAS permission denied")).when(reader).deleteExact("", filename);
        var first = service().start();
        assertEquals(1, first.get("succeeded"));
        assertEquals(1, first.get("cleanupPending"));
        assertEquals("Series", gallery.getStoragePath());
        assertEquals(filename, gallery.getSeriesCleanupFilename());
        assertEquals("", gallery.getSeriesCleanupPath());
        assertTrue(Files.exists(root.resolve(filename)));
        var order = inOrder(mapper, reader);
        order.verify(mapper).update(isNull(), any());
        order.verify(reader).deleteExact("", filename);

        doCallRealMethod().when(reader).deleteExact("", filename);
        assertEquals(1, service().start().get("succeeded"));
        assertNull(gallery.getSeriesCleanupFilename());
        assertFalse(Files.exists(root.resolve(filename)));
        assertTrue(Files.exists(root.resolve("Series/" + filename)));
        verify(uploader, times(1)).upload(any(), anyString(), anyString(), any());
    }

    @Test
    void failedDatabaseCommitNeverDeletesSourceAndCanBeRetried() throws Exception {
        archive(root.resolve(filename));
        when(mapper.update(isNull(), any())).thenThrow(new IllegalStateException("DB unavailable"));
        assertEquals(1, service().start().get("failed"));
        assertTrue(Files.exists(root.resolve(filename)));
        assertNull(gallery.getStoragePath());
        verify(reader, never()).deleteExact(anyString(), anyString());
        doReturn(1).when(mapper).update(isNull(), any());
        assertEquals(1, service().start().get("succeeded"));
        assertFalse(Files.exists(root.resolve(filename)));
        assertEquals(filename, gallery.getFilename());
    }

    @Test
    void rejectsMultipleDestinationCandidatesWithoutDeletingOrPublishing() throws Exception {
        archive(root.resolve("Series/" + filename));
        archive(root.resolve("Series/[561583] another title.zip"));
        assertEquals(1, service().start().get("failed"));
        verifyNoInteractions(uploader);
        verify(mapper, never()).update(isNull(), any());
        verify(reader, never()).deleteExact(anyString(), anyString());
    }

    @Test
    void corruptRecoveredArchiveCannotUpdateDatabaseOrOverwriteDestination() throws Exception {
        Path path = root.resolve("Series/" + filename);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "broken archive");
        assertEquals(1, service().start().get("failed"));
        assertEquals("broken archive", Files.readString(path));
        verifyNoInteractions(uploader);
        verify(mapper, never()).update(isNull(), any());
    }

    @Test
    void missingDestinationPreventsPendingCleanup() throws Exception {
        archive(root.resolve(filename));
        gallery.setFilename(filename);
        gallery.setStoragePath("Series");
        gallery.setSeriesSyncSignature("1:Series:0");
        gallery.setSeriesCleanupPath("");
        gallery.setSeriesCleanupFilename(filename);
        assertEquals(1, service().start().get("failed"));
        assertTrue(Files.exists(root.resolve(filename)));
        verify(reader, never()).deleteExact(anyString(), anyString());
    }

    @Test
    void alreadyDeletedSourceFinishesPendingCleanup() throws Exception {
        archive(root.resolve("Series/" + filename));
        gallery.setFilename(filename);
        gallery.setStoragePath("Series");
        gallery.setSeriesSyncSignature("1:Series:0");
        gallery.setSeriesCleanupPath("");
        gallery.setSeriesCleanupFilename(filename);
        assertEquals(1, service().start().get("succeeded"));
        assertNull(gallery.getSeriesCleanupFilename());
        assertTrue(Files.exists(root.resolve("Series/" + filename)));
        verifyNoInteractions(uploader);
    }

    @Test
    void uploadFailureLeavesDatabaseAndSourceUntouched() throws Exception {
        archive(root.resolve(filename));
        doThrow(new IOException("upload failed")).when(uploader).upload(any(), anyString(), anyString(), any());
        assertEquals(1, service().start().get("failed"));
        assertNull(gallery.getStoragePath());
        assertTrue(Files.exists(root.resolve(filename)));
        verify(mapper, never()).update(isNull(), any());
    }
}
