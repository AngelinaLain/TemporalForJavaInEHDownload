package com.checker.service;

import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KomgaIdRefreshServiceTest {
    private EhGalleriesMapper mapper;
    private KomgaMediaClient client;
    private GalleryMediaService previews;

    @BeforeEach void setup() {
        mapper = mock(EhGalleriesMapper.class);
        client = mock(KomgaMediaClient.class);
        previews = mock(GalleryMediaService.class);
        when(client.configured()).thenReturn(true);
        when(mapper.getKomgaIdRefreshBounds(anyList())).thenReturn(Map.of("maxGid", 42L, "total", 1L));
        when(mapper.compareAndSetKomgaBookId(anyLong(), any(), anyString(), any(), any())).thenReturn(1);
    }

    @Test void refreshUpdatesOnlyAnObsoleteMappingAndInvalidatesItsPreview() throws Exception {
        EhGalleriesEntity gallery = row(42, "old-id");
        when(mapper.getKomgaIdRefreshBatch(0, 42, List.of(), 200)).thenReturn(List.of(gallery));
        catalog(book("new-id", "[42] Renamed title.cbz"));
        Map<String, Object> state = service(Runnable::run).start(null);
        assertEquals("COMPLETED", state.get("status"));
        assertEquals(1L, state.get("updated"));
        assertEquals(1L, state.get("processed"));
        assertEquals(0L, state.get("failed"));
        verify(mapper).compareAndSetKomgaBookId(42, "old-id", "new-id", "[42] Historical.cbz", "old-series");
        verify(previews).invalidate(42);
        verify(client).librarySnapshot(any(BooleanSupplier.class));
        verify(client, never()).findBook(anyLong(), any());
        assertEquals("UPDATED", results(state).get(0).get("status"));
        assertEquals("old-id", results(state).get(0).get("oldId"));
        assertEquals("new-id", results(state).get(0).get("newId"));
    }

    @Test void validMappingsRemainUnchangedButWrongPhysicalIdentityIsAConflict() throws Exception {
        when(mapper.getKomgaIdRefreshBounds(anyList())).thenReturn(Map.of("maxGid", 43L, "total", 2L));
        when(mapper.getKomgaIdRefreshBatch(0, 43, List.of(), 200))
                .thenReturn(List.of(row(42, "valid-id"), row(43, "wrong-id")));
        catalog(book("valid-id", "[42] Saved.zip"), book("wrong-id", "[440] Another.cbz"), book("replacement-43", "[43] Actual.cbz"));
        Map<String, Object> state = service(Runnable::run).start(null);
        assertEquals(1L, state.get("unchanged"));
        assertEquals(1L, state.get("conflicts"));
        assertEquals(0L, state.get("updated"));
        assertEquals("CONFLICT", results(state).get(0).get("status"));
        verify(mapper, never()).compareAndSetKomgaBookId(anyLong(), any(), anyString(), any(), any());
        verifyNoInteractions(previews);
    }

    @Test void missingAndAmbiguousMatchesPreserveDatabaseIds() throws Exception {
        when(mapper.getKomgaIdRefreshBounds(anyList())).thenReturn(Map.of("maxGid", 43L, "total", 2L));
        when(mapper.getKomgaIdRefreshBatch(0, 43, List.of(), 200))
                .thenReturn(List.of(row(42, "stale"), row(43, null)));
        catalog(book("first", "[42] First.cbz"), book("second", "[42] Second.zip"));
        Map<String, Object> state = service(Runnable::run).start(null);
        assertEquals(1L, state.get("ambiguous"));
        assertEquals(1L, state.get("notFound"));
        assertEquals(0L, state.get("updated"));
        verify(mapper, never()).compareAndSetKomgaBookId(anyLong(), any(), anyString(), any(), any());
    }

    @Test void filenameFallbackCannotOverrideAnExplicitDifferentPhysicalGid() throws Exception {
        EhGalleriesEntity gallery = row(42, null); gallery.setFilename("Historical.cbz");
        when(mapper.getKomgaIdRefreshBatch(0, 42, List.of(), 200)).thenReturn(List.of(gallery));
        catalog(new KomgaMediaClient.LibraryBook("wrong", List.of("Historical", "[420] Historical.cbz")));
        Map<String, Object> state = service(Runnable::run).start(null);
        assertEquals(1L, state.get("notFound"));
        verify(mapper, never()).compareAndSetKomgaBookId(anyLong(), any(), anyString(), any(), any());
    }

    @Test void nullIdsCanBeFilledThroughUniqueLegacyFilenameAndCasRejectsConcurrentChanges() throws Exception {
        EhGalleriesEntity gallery = row(42, null); gallery.setFilename("Historical.zip");
        when(mapper.getKomgaIdRefreshBatch(0, 42, List.of(42L), 200)).thenReturn(List.of(gallery));
        catalog(book("new-id", "Historical.cbz"));
        when(mapper.compareAndSetKomgaBookId(42, null, "new-id", "Historical.zip", "old-series")).thenReturn(0);
        Map<String, Object> state = service(Runnable::run).start(List.of(42L, 42L));
        assertEquals(1L, state.get("conflicts"));
        assertEquals(0L, state.get("updated"));
        verify(mapper).compareAndSetKomgaBookId(42, null, "new-id", "Historical.zip", "old-series");
        verifyNoInteractions(previews);
    }

    @Test void databasePagingUsesAFixedUpperCursorAndDoesNotReadWholeEntities() throws Exception {
        when(mapper.getKomgaIdRefreshBounds(anyList())).thenReturn(Map.of("maxGid", 500L, "total", 3L));
        when(mapper.getKomgaIdRefreshBatch(0, 500, List.of(), 200)).thenReturn(List.of(row(1, "a"), row(2, "b")));
        when(mapper.getKomgaIdRefreshBatch(2, 500, List.of(), 200)).thenReturn(List.of(row(500, "c")));
        catalog();
        Map<String, Object> state = service(Runnable::run).start(null);
        assertEquals(3L, state.get("processed"));
        assertEquals(3L, state.get("notFound"));
        verify(mapper).getKomgaIdRefreshBatch(0, 500, List.of(), 200);
        verify(mapper).getKomgaIdRefreshBatch(2, 500, List.of(), 200);
        verify(mapper, never()).selectList(any());
    }

    @Test void cancellationAndDuplicateStartsDoNotBeginAnotherJobOrWriteMappings() throws Exception {
        AtomicReference<Runnable> pending = new AtomicReference<>();
        KomgaIdRefreshService service = service(pending::set);
        assertEquals(true, service.start(null).get("running"));
        assertThrows(IllegalStateException.class, () -> service.start(null));
        assertEquals(true, service.cancel().get("cancelRequested"));
        pending.get().run();
        assertEquals("CANCELLED", service.status().get("status"));
        assertEquals(false, service.status().get("running"));
        verify(client, never()).librarySnapshot(any());
        verify(mapper, never()).compareAndSetKomgaBookId(anyLong(), any(), anyString(), any(), any());
    }

    @Test void incompleteKomgaCatalogNeverPerformsAnyDatabaseWrite() throws Exception {
        when(client.librarySnapshot(any())).thenThrow(new IOException("Komga 图库分页不完整"));
        Map<String, Object> state = service(Runnable::run).start(null);
        assertEquals("FAILED", state.get("status"));
        assertEquals(0L, state.get("processed"));
        assertTrue(String.valueOf(state.get("lastError")).contains("不完整"));
        verify(mapper, never()).getKomgaIdRefreshBatch(anyLong(), anyLong(), anyList(), anyInt());
        verify(mapper, never()).compareAndSetKomgaBookId(anyLong(), any(), anyString(), any(), any());
    }

    @Test void resultDetailsAreBoundedWithoutLosingCounters() throws Exception {
        when(mapper.getKomgaIdRefreshBounds(anyList())).thenReturn(Map.of("maxGid", 205L, "total", 205L));
        List<EhGalleriesEntity> first = new ArrayList<>(), last = new ArrayList<>();
        for (int gid = 1; gid <= 205; gid++) (gid <= 200 ? first : last).add(row(gid, "stale"));
        when(mapper.getKomgaIdRefreshBatch(0, 205, List.of(), 200)).thenReturn(first);
        when(mapper.getKomgaIdRefreshBatch(200, 205, List.of(), 200)).thenReturn(last);
        catalog();
        Map<String, Object> state = service(Runnable::run).start(null);
        assertEquals(205L, state.get("processed"));
        assertEquals(205L, state.get("resultsTotal"));
        assertEquals(200, results(state).size());
        assertEquals(true, state.get("resultsTruncated"));
    }

    @Test void invalidScopesAndRejectedExecutionDoNotTouchTheDatabase() {
        KomgaIdRefreshService service = service(Runnable::run);
        assertThrows(IllegalArgumentException.class, () -> service.start(List.of(0L)));
        assertThrows(IllegalArgumentException.class, () -> service.start(java.util.Collections.nCopies(1001, 42L)));
        assertThrows(IllegalStateException.class, () -> service(command -> { throw new IllegalStateException("rejected"); }).start(null));
        verifyNoInteractions(mapper);
    }

    @Test void committedDatabaseUpdateIsNotReportedAsFailureWhenCacheInvalidationFails() throws Exception {
        when(mapper.getKomgaIdRefreshBatch(0, 42, List.of(), 200)).thenReturn(List.of(row(42, "old")));
        catalog(book("new", "[42] Actual.cbz"));
        doThrow(new IllegalStateException("cache unavailable")).when(previews).invalidate(42);
        Map<String, Object> state = service(Runnable::run).start(null);
        assertEquals(1L, state.get("updated"));
        assertEquals(0L, state.get("failed"));
        assertEquals("UPDATED", results(state).get(0).get("status"));
        assertTrue(String.valueOf(results(state).get(0).get("message")).contains("自动过期"));
    }

    @Test void staleBrowserCannotCancelAReplacementJob() {
        AtomicReference<Runnable> pending = new AtomicReference<>();
        KomgaIdRefreshService service = service(pending::set);
        String firstId = (String) service.start(null).get("jobId");
        service.cancel(firstId); pending.get().run();
        String secondId = (String) service.start(null).get("jobId");
        assertNotEquals(firstId, secondId);
        assertThrows(IllegalStateException.class, () -> service.cancel(firstId));
        assertEquals(false, service.status().get("cancelRequested"));
        assertEquals(true, service.status().get("running"));
        assertEquals(true, service.cancel(secondId).get("cancelRequested"));
        pending.get().run();
        assertEquals("CANCELLED", service.status().get("status"));
    }

    private KomgaIdRefreshService service(TaskExecutor executor) { return new KomgaIdRefreshService(mapper, client, previews, executor); }
    private void catalog(KomgaMediaClient.LibraryBook... books) throws IOException {
        when(client.librarySnapshot(any())).thenReturn(new KomgaMediaClient.LibrarySnapshot(List.of(books), books.length));
    }
    private static KomgaMediaClient.LibraryBook book(String id, String name) { return new KomgaMediaClient.LibraryBook(id, List.of(name)); }
    private static EhGalleriesEntity row(long gid, String oldId) {
        EhGalleriesEntity gallery = new EhGalleriesEntity();
        gallery.setGid(gid); gallery.setKomgaBookId(oldId); gallery.setFilename("[" + gid + "] Historical.cbz");
        gallery.setStoragePath("old-series"); gallery.setTitle("Gallery " + gid);
        return gallery;
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> results(Map<String, Object> state) {
        return (List<Map<String, Object>>) state.get("results");
    }
}
