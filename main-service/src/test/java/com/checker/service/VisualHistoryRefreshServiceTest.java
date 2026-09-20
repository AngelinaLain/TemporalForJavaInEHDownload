package com.checker.service;

import com.checker.entity.EhGalleriesEntity;
import com.checker.entity.VisualRefreshFailureEntity;
import com.checker.entity.VisualRefreshJobEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.mapper.VisualRefreshFailureMapper;
import com.checker.mapper.VisualRefreshJobMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class VisualHistoryRefreshServiceTest {
    private final EhGalleriesMapper galleriesMapper = mock(EhGalleriesMapper.class);
    private final VisualRefreshJobMapper jobMapper = mock(VisualRefreshJobMapper.class);
    private final VisualRefreshFailureMapper failureMapper = mock(VisualRefreshFailureMapper.class);
    private final VisualFingerprintService fingerprintService = mock(VisualFingerprintService.class);
    private final ArchiveVisualFingerprintExtractor extractor = mock(ArchiveVisualFingerprintExtractor.class);
    private final SynologyArchiveReader archiveReader = mock(SynologyArchiveReader.class);
    private final SynologyArchiveReader.ArchiveSession archiveSession =
            mock(SynologyArchiveReader.ArchiveSession.class);
    private final DedupeReviewService reviewService = mock(DedupeReviewService.class);
    private final AtomicReference<VisualRefreshJobEntity> storedJob = new AtomicReference<>();
    private VisualHistoryRefreshService service;

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> {
            storedJob.set(invocation.getArgument(0));
            return 1;
        }).when(jobMapper).insert(any());
        when(jobMapper.selectById(anyString())).thenAnswer(invocation -> storedJob.get());
        when(archiveReader.openSession()).thenReturn(archiveSession);
        service = new VisualHistoryRefreshService(galleriesMapper, jobMapper, failureMapper,
                fingerprintService, extractor, archiveReader, reviewService, Runnable::run);
    }

    @Test
    void persistsEveryGalleryFailureWithItsGidAndError() throws Exception {
        when(galleriesMapper.selectList(any())).thenReturn(List.of(
                gallery(101L, "first.cbz"), gallery(202L, "second.cbz")));
        when(fingerprintService.hasArchiveFingerprints(any())).thenReturn(false);
        when(archiveSession.read(anyString(), any())).thenThrow(new IOException("NAS unavailable"));

        VisualRefreshJobEntity result = service.start(false);

        assertEquals("COMPLETED_WITH_ERRORS", result.getStatus());
        assertEquals(2, result.getProcessed());
        assertEquals(2, result.getFailed());
        verify(failureMapper).insert(failure(101L, "NAS unavailable"));
        verify(failureMapper).insert(failure(202L, "NAS unavailable"));
    }

    @Test
    void nonForcedRefreshStillSkipsGalleriesWithArchiveFingerprints() throws Exception {
        when(galleriesMapper.selectList(any())).thenReturn(List.of(
                gallery(1L, "existing.cbz"), gallery(2L, "missing.cbz")));
        when(fingerprintService.hasArchiveFingerprints(1L)).thenReturn(true);
        when(fingerprintService.hasArchiveFingerprints(2L)).thenReturn(false);
        when(archiveSession.read(anyString(), any())).thenReturn(1);

        VisualRefreshJobEntity result = service.start(false);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getSucceeded());
        verify(archiveSession, never()).read(org.mockito.ArgumentMatchers.eq("existing.cbz"), any());
        verify(archiveSession).read(org.mockito.ArgumentMatchers.eq("missing.cbz"), any());
    }

    @Test
    void selectiveRetryProcessesOnlyChosenGidsEvenWhenFingerprintExists() throws Exception {
        when(galleriesMapper.selectList(any())).thenReturn(List.of(
                gallery(10L, "chosen.cbz"), gallery(20L, "other.cbz")));
        when(fingerprintService.hasArchiveFingerprints(10L)).thenReturn(true);
        when(archiveSession.read(anyString(), any())).thenReturn(1);

        VisualRefreshJobEntity result = service.retry(List.of(10L, 10L));

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getSucceeded());
        verify(archiveSession).read(org.mockito.ArgumentMatchers.eq("chosen.cbz"), any());
        verify(archiveSession, never()).read(org.mockito.ArgumentMatchers.eq("other.cbz"), any());
        verify(fingerprintService, never()).hasArchiveFingerprints(any());
    }

    @Test
    void reusesOneArchiveSessionForTheWholeRefreshJob() throws Exception {
        when(galleriesMapper.selectList(any())).thenReturn(List.of(
                gallery(1L, "first.cbz"), gallery(2L, "second.cbz")));
        when(fingerprintService.hasArchiveFingerprints(any())).thenReturn(false);
        when(archiveSession.read(anyString(), any())).thenReturn(1);

        VisualRefreshJobEntity result = service.start(false);

        assertEquals(2, result.getSucceeded());
        verify(archiveReader, times(1)).openSession();
        verify(archiveSession, times(2)).read(anyString(), any());
        verify(archiveSession).close();
    }

    @Test
    void marksInterruptedRunningJobAsFailedWhenNoLocalTaskExists() {
        VisualRefreshJobEntity stale = new VisualRefreshJobEntity();
        stale.setId("stale");
        stale.setStatus("RUNNING");
        when(jobMapper.selectOne(any())).thenReturn(stale);

        VisualRefreshJobEntity result = service.latest();

        assertEquals("FAILED", result.getStatus());
        assertEquals("服务已重启或任务已中断，请重新执行失败项", result.getLastError());
        verify(jobMapper).updateById(stale);
    }

    private EhGalleriesEntity gallery(long gid, String filename) {
        EhGalleriesEntity gallery = new EhGalleriesEntity();
        gallery.setGid(gid);
        gallery.setFilename(filename);
        gallery.setPageCount(10);
        return gallery;
    }

    private VisualRefreshFailureEntity failure(long gid, String error) {
        return org.mockito.ArgumentMatchers.argThat(value ->
                value != null && value.getGid() == gid && error.equals(value.getError())
                        && value.getJobId() != null);
    }
}
