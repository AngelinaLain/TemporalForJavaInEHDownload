package com.checker.service.rss;

import com.checker.entity.EhGalleriesEntity;
import com.checker.dao.catalog.EhGalleriesMapper;
import com.checker.service.catalog.GalleryMediaService;
import com.checker.manager.collection.GallerySeriesPlacementService;
import com.checker.manager.komga.KomgaMediaClient;
import com.checker.service.komga.KomgaSeriesSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RssCollectionArchiveServiceTest {
    private final KomgaSeriesSyncService sync = mock(KomgaSeriesSyncService.class);
    private final GallerySeriesPlacementService placements = mock(GallerySeriesPlacementService.class);
    private final EhGalleriesMapper galleries = mock(EhGalleriesMapper.class);
    private final KomgaMediaClient komga = mock(KomgaMediaClient.class);
    private final GalleryMediaService previews = mock(GalleryMediaService.class);
    private final RssCollectionArchiveService service = new RssCollectionArchiveService(sync, placements, galleries, komga, previews);
    private EhGalleriesEntity gallery;
    private static final String FILE = "[42] Title.cbz";

    @BeforeEach void setUp() throws Exception {
        gallery = new EhGalleriesEntity();
        gallery.setGid(42L);
        gallery.setDownloadStatus("IMPORTED");
        gallery.setFilename(FILE);
        gallery.setStoragePath("RSS Series");
        gallery.setSeriesSyncSignature("8:RSS Series:0");
        gallery.setKomgaBookId("old-id");
        when(galleries.selectById(42L)).thenReturn(gallery);
        when(placements.resolve(42L)).thenReturn(
                new GallerySeriesPlacementService.SeriesPlacement(8L, "RSS Series", "RSS Series", 1, "8:RSS Series:0"));
        when(komga.findBookAtPath(42L, "RSS Series", FILE)).thenReturn(Optional.empty());
        when(galleries.compareAndSetKomgaBookId(42L, "old-id", "new-id", FILE, "RSS Series")).thenReturn(1);
    }

    @Test void waitsForTheDestinationBookAndThenUpdatesItsIdWithoutChangingDownloadState() throws Exception {
        assertFalse(service.synchronize(42L, 8L));
        verify(galleries, never()).compareAndSetKomgaBookId(anyLong(), any(), any(), any(), any());
        assertEquals("IMPORTED", gallery.getDownloadStatus());
        when(komga.findBookAtPath(42L, "RSS Series", FILE)).thenReturn(Optional.of("new-id"));
        assertTrue(service.synchronize(42L, 8L));
        verify(sync, times(2)).synchronizeOne(42L, 8L);
        verify(galleries).compareAndSetKomgaBookId(42L, "old-id", "new-id", FILE, "RSS Series");
        verify(previews, times(3)).invalidate(42L);
        verify(galleries, never()).update(any(), any());
    }

    @Test void archiveOrKomgaFailuresAreRetriableAndNeverBecomeSuccessfulAssignments() throws Exception {
        doThrow(new IOException("storage unavailable")).doNothing().when(sync).synchronizeOne(42L, 8L);
        assertThrows(IOException.class, () -> service.synchronize(42L, 8L));
        verifyNoInteractions(komga);
        when(komga.findBookAtPath(42L, "RSS Series", FILE)).thenThrow(new IOException("Komga unavailable"));
        assertThrows(IOException.class, () -> service.synchronize(42L, 8L));
        doReturn(Optional.of("new-id")).when(komga).findBookAtPath(42L, "RSS Series", FILE);
        assertTrue(service.synchronize(42L, 8L));
        verify(galleries).compareAndSetKomgaBookId(42L, "old-id", "new-id", FILE, "RSS Series");
    }

    @Test void refusesToOverwriteAConcurrentBookIdOrMembershipChange() throws Exception {
        when(komga.findBookAtPath(42L, "RSS Series", FILE)).thenReturn(Optional.of("new-id"));
        when(galleries.compareAndSetKomgaBookId(42L, "old-id", "new-id", FILE, "RSS Series")).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.synchronize(42L, 8L));
        clearInvocations(galleries);
        when(komga.findBookAtPath(42L, "RSS Series", FILE)).thenAnswer(invocation -> {
            when(placements.resolve(42L)).thenReturn(
                    new GallerySeriesPlacementService.SeriesPlacement(9L, "Manual", "Manual", 1, "9:Manual:0"));
            return Optional.of("new-id");
        });
        assertThrows(IllegalStateException.class, () -> service.synchronize(42L, 8L));
        verify(galleries, never()).compareAndSetKomgaBookId(anyLong(), any(), any(), any(), any());
    }

    @Test void pendingCleanupOrUnconfirmedImportsCannotRefreshTheAssociation() {
        gallery.setSeriesCleanupFilename(FILE);
        assertThrows(IllegalStateException.class, () -> service.synchronize(42L, 8L));
        gallery.setSeriesCleanupFilename(null);
        gallery.setDownloadStatus("WAITING_KOMGA");
        assertThrows(IllegalStateException.class, () -> service.synchronize(42L, 8L));
        verifyNoInteractions(komga);
    }
}
