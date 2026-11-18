package com.checker.service.dedupe;

import com.checker.dedupe.PerceptualHash;
import com.checker.dao.dedupe.AiVisualReviewJobMapper;
import com.checker.dao.dedupe.AiVisualReviewResultMapper;
import com.checker.dao.dedupe.GalleryPageEmbeddingMapper;
import com.checker.manager.dedupe.ArchiveVisualFingerprintExtractor;
import com.checker.model.dataobject.dedupe.AiVisualReviewJobEntity;
import com.checker.model.dataobject.dedupe.AiVisualReviewResultEntity;
import com.checker.service.aiconfig.AiConfigurationService;
import com.checker.manager.ai.AiInternalClient;
import com.checker.manager.archive.ArchiveSampleImageExtractor;
import com.checker.manager.archive.SynologyArchiveReader;
import com.checker.dto.GalleryPageFingerprint;
import com.checker.dto.VisualEmbedding;
import com.checker.entity.EhGalleriesEntity;
import com.checker.dao.catalog.EhGalleriesMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VisualEmbeddingReviewServiceTest {
    private static final long LEFT_GID = 3159496L;
    private static final long RIGHT_GID = 3159497L;
    private final AiVisualReviewJobMapper jobs = mock(AiVisualReviewJobMapper.class);
    private final AiVisualReviewResultMapper results = mock(AiVisualReviewResultMapper.class);
    private final GalleryPageEmbeddingMapper embeddings = mock(GalleryPageEmbeddingMapper.class);
    private final EhGalleriesMapper galleries = mock(EhGalleriesMapper.class);
    private final VisualFingerprintService fingerprints = mock(VisualFingerprintService.class);
    private final ArchiveVisualFingerprintExtractor fingerprintExtractor = mock(ArchiveVisualFingerprintExtractor.class);
    private final SynologyArchiveReader archives = mock(SynologyArchiveReader.class);
    private final SynologyArchiveReader.ArchiveSession session = mock(SynologyArchiveReader.ArchiveSession.class);
    private final ArchiveSampleImageExtractor samples = mock(ArchiveSampleImageExtractor.class);
    private final AiInternalClient ai = mock(AiInternalClient.class);
    private final AiConfigurationService configuration = mock(AiConfigurationService.class);
    private final AiVisualReviewJobEntity job = new AiVisualReviewJobEntity();
    private final EhGalleriesEntity left = gallery(LEFT_GID);
    private final EhGalleriesEntity right = gallery(RIGHT_GID);
    private final List<GalleryPageFingerprint> leftHashes = List.of(hash(LEFT_GID));
    private final List<GalleryPageFingerprint> rightHashes = List.of(hash(RIGHT_GID));
    private VisualEmbeddingReviewService service;

    @BeforeEach
    void setUp() throws Exception {
        job.setId("review");
        job.setLeftGid(LEFT_GID);
        job.setRightGid(RIGHT_GID);
        job.setStatus("QUEUED");
        job.setAnalysisMode("EMBEDDING_ONLY");
        when(jobs.selectById(job.getId())).thenReturn(job);
        when(galleries.selectById(LEFT_GID)).thenReturn(left);
        when(galleries.selectById(RIGHT_GID)).thenReturn(right);
        when(fingerprints.find(LEFT_GID)).thenReturn(leftHashes);
        when(fingerprints.find(RIGHT_GID)).thenReturn(rightHashes);
        when(archives.openSession()).thenReturn(session);
        when(session.listArchives(anyString())).thenReturn(List.of(left.getFilename(), right.getFilename()));
        when(session.read(anyString(), anyString(), any())).thenAnswer(invocation -> {
            SynologyArchiveReader.ArchiveInputFunction<?> function = invocation.getArgument(2);
            return function.apply(new ByteArrayInputStream(new byte[0]));
        });
        when(samples.extract(any(), eq(Set.of(0)))).thenReturn(Map.of(0,
                new ArchiveSampleImageExtractor.SampleImage(0, "page.jpg", "image/jpeg", new byte[]{1, 2})));
        when(ai.embeddingStatus()).thenReturn(Map.of("available", true, "modelVersion", "test-v1"));
        when(ai.embed(any(), anyString())).thenReturn(VisualEmbedding.builder()
                .model("test").modelVersion("test-v1").dimensions(2).values(new float[]{1, 0}).build());
        when(embeddings.selectList(any())).thenReturn(List.of());
        service = new VisualEmbeddingReviewService(jobs, results, embeddings, galleries, fingerprints,
                fingerprintExtractor, archives, samples, ai, configuration, new ObjectMapper());
    }

    @Test
    void reusesCurrentFingerprintsWithoutRegeneratingThem() throws Exception {
        service.execute(job.getId());

        assertCompletedWithEvidence();
        verifyNoInteractions(fingerprintExtractor);
        verify(fingerprints, never()).replace(anyLong(), anyList());
    }

    @ParameterizedTest
    @CsvSource({"true, false", "false, true", "true, true"})
    void generatesAndPersistsMissingFingerprintsBeforeCompletingReview(boolean missingLeft,
                                                                      boolean missingRight) throws Exception {
        if (missingLeft) missingFingerprints(LEFT_GID, leftHashes);
        if (missingRight) missingFingerprints(RIGHT_GID, rightHashes);

        service.execute(job.getId());

        assertCompletedWithEvidence();
        verifyGeneration(LEFT_GID, leftHashes, missingLeft);
        verifyGeneration(RIGHT_GID, rightHashes, missingRight);
        verify(session, atLeastOnce()).close();
    }

    @Test
    void findsMissingFingerprintsInArchiveRootWhenRecordedDirectoryIsGone() throws Exception {
        missingFingerprints(LEFT_GID, leftHashes);
        left.setStoragePath("missing-series");
        left.setSeriesCleanupPath("old-series");
        when(session.listArchives("missing-series")).thenThrow(new NoSuchFileException("missing-series"));
        when(session.listArchives("old-series")).thenReturn(List.of());

        service.execute(job.getId());

        assertCompletedWithEvidence();
        verify(fingerprints).replace(LEFT_GID, leftHashes);
        verify(session, times(2)).read(eq(""), eq(left.getFilename()), any());
    }

    @Test
    void recordsMissingArchiveAsFailureWithoutCallingTheModel() throws Exception {
        when(fingerprints.find(LEFT_GID)).thenReturn(List.of());
        when(session.listArchives(anyString())).thenReturn(List.of());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> service.execute(job.getId()));

        assertTrue(failure.getMessage().contains(String.valueOf(LEFT_GID)));
        assertFailedWithoutResult();
        verifyNoInteractions(fingerprintExtractor, ai);
        verify(session).close();
    }

    @Test
    void recordsFingerprintExtractionFailureWithoutCallingTheModel() throws Exception {
        when(fingerprints.find(LEFT_GID)).thenReturn(List.of());
        when(fingerprintExtractor.extract(any(), eq(LEFT_GID), eq(1)))
                .thenThrow(new IOException("corrupt sampled images"));

        assertThrows(Exception.class, () -> service.execute(job.getId()));

        assertEquals("corrupt sampled images", job.getLastError());
        assertFailedWithoutResult();
        verify(fingerprints, never()).replace(anyLong(), anyList());
        verifyNoInteractions(ai);
        verify(session).close();
    }

    @Test
    void failsWithoutResultWhenArchiveProducesNoFingerprints() throws Exception {
        when(fingerprints.find(LEFT_GID)).thenReturn(List.of());
        when(fingerprintExtractor.extract(any(), eq(LEFT_GID), eq(1))).thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> service.execute(job.getId()));

        assertFailedWithoutResult();
        verifyNoInteractions(ai);
    }

    private void missingFingerprints(long gid, List<GalleryPageFingerprint> generated) throws Exception {
        when(fingerprints.find(gid)).thenReturn(List.of(), generated);
        when(fingerprintExtractor.extract(any(), eq(gid), eq(1))).thenReturn(generated);
        when(fingerprints.replace(gid, generated)).thenReturn(generated.size());
    }

    private void verifyGeneration(long gid, List<GalleryPageFingerprint> generated, boolean expected) throws Exception {
        verify(fingerprintExtractor, times(expected ? 1 : 0)).extract(any(), eq(gid), eq(1));
        verify(fingerprints, times(expected ? 1 : 0)).replace(gid, generated);
    }

    private void assertCompletedWithEvidence() {
        assertEquals("COMPLETED", job.getStatus());
        assertNull(job.getLastError());
        assertNotNull(job.getFinishedAt());
        ArgumentCaptor<AiVisualReviewResultEntity> result = ArgumentCaptor.forClass(AiVisualReviewResultEntity.class);
        verify(results).insert(result.capture());
        assertEquals("SAME_CONTENT", result.getValue().getDecision());
        assertEquals(1, result.getValue().getComparedPages());
    }

    private void assertFailedWithoutResult() {
        assertEquals("FAILED", job.getStatus());
        assertNotNull(job.getFinishedAt());
        assertNotNull(job.getLastError());
        verify(jobs, times(2)).updateById(job);
        verifyNoInteractions(results);
    }

    private static EhGalleriesEntity gallery(long gid) {
        EhGalleriesEntity gallery = new EhGalleriesEntity();
        gallery.setGid(gid);
        gallery.setFilename("[" + gid + "] archive.cbz");
        gallery.setPageCount(1);
        return gallery;
    }

    private static GalleryPageFingerprint hash(long gid) {
        return GalleryPageFingerprint.builder().gid(gid).pageIndex(0).pageName("page.jpg")
                .source("ARCHIVE").perceptualHash("0000000000000000").centerHash("0000000000000000")
                .algorithmVersion(PerceptualHash.ALGORITHM_VERSION).build();
    }
}
