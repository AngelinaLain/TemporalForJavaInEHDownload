package com.checker.service;

import com.checker.common.DedupeSettings;
import com.checker.common.DownloadStatus;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.temporalServices.activities.impl.DatabaseActivityImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseActivityDedupeSettingsTest {
    @Test void oneDownloadClaimUsesOneSnapshotForReviewHoldingClusteringAndScores() {
        EhGalleriesMapper galleries = mock(EhGalleriesMapper.class);
        DedupeReviewService reviews = mock(DedupeReviewService.class);
        DatabaseActivityImpl activity = new DatabaseActivityImpl();
        ReflectionTestUtils.setField(activity, "galleriesMapper", galleries);
        ReflectionTestUtils.setField(activity, "dedupeReviewService", reviews);
        EhGalleriesEntity gallery = new EhGalleriesEntity();
        gallery.setGid(42L); gallery.setCandidateKey("bucket"); gallery.setDownloadStatus(DownloadStatus.PENDING.getValue());
        List<EhGalleriesEntity> bucket = List.of(gallery);
        DedupeSettings snapshot = new DedupeSettings(70, 92, 90, 12, 5, 85, 8);
        when(galleries.selectById(42L)).thenReturn(gallery);
        when(galleries.selectList(any())).thenReturn(bucket);
        when(reviews.settingsSnapshot()).thenReturn(snapshot)
                .thenThrow(new AssertionError("本次认领不能再次读取变化中的配置"));
        when(reviews.findByCandidateKey("bucket")).thenReturn(List.of());
        when(reviews.heldGids(eq(bucket), eq(List.of()), same(snapshot))).thenReturn(Set.of());
        when(reviews.cluster(eq(bucket), eq(List.of()), same(snapshot))).thenReturn(List.of(bucket));
        when(reviews.choosePreferred(eq(bucket), eq(List.of()))).thenReturn(gallery);

        assertTrue(activity.claimGalleryForDownload(42L));
        verify(reviews).settingsSnapshot();
        verify(reviews).ensureGrayZoneReviews(eq("bucket"), eq(bucket), same(snapshot));
        verify(reviews).heldGids(eq(bucket), eq(List.of()), same(snapshot));
        verify(reviews).cluster(eq(bucket), eq(List.of()), same(snapshot));
        verify(galleries).update(isNull(), any());
    }
}
