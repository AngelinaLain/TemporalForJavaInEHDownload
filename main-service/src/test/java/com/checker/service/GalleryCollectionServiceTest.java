package com.checker.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.checker.dto.GalleryCollectionCandidate;
import com.checker.entity.EhGalleriesEntity;
import com.checker.entity.GalleryCollectionEntity;
import com.checker.mapper.GalleryCollectionMapper;
import com.checker.mapper.GalleryPageHashMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GalleryCollectionServiceTest {
    @Mock
    private GalleryCollectionMapper collectionMapper;
    @Mock
    private EhGalleriesService galleriesService;
    @Mock
    private GalleryPageHashMapper pageHashMapper;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void scansCandidatesBeyondFirstBatchAndKeepsOnlyTopResults() {
        GalleryCollectionEntity collection = new GalleryCollectionEntity();
        collection.setId(1L);
        when(collectionMapper.selectById(1L)).thenReturn(collection);
        when(jdbcTemplate.queryForList(any(String.class), eq(Long.class), anyLong()))
                .thenReturn(List.of(1L));

        EhGalleriesEntity member = gallery(1L, "Example Story Vol. 1");
        when(galleriesService.listByIds(any())).thenReturn(List.of(member));
        when(pageHashMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        List<EhGalleriesEntity> firstBatch = new ArrayList<>();
        for (long gid = 2; gid <= 501; gid++) firstBatch.add(gallery(gid, "Example Story Vol. " + gid));
        EhGalleriesEntity beyondOldLimit = gallery(502L, "Example Story Vol. 502");
        when(galleriesService.list(any(QueryWrapper.class)))
                .thenReturn(firstBatch, List.of(beyondOldLimit));

        GalleryCollectionService service = new GalleryCollectionService(
                collectionMapper, galleriesService, pageHashMapper, jdbcTemplate);
        List<GalleryCollectionCandidate> result = service.suggestions(1L, 10);

        assertEquals(10, result.size());
        assertEquals(502L, result.get(0).getGid());
        verify(galleriesService, org.mockito.Mockito.times(2)).list(any(QueryWrapper.class));
    }

    private EhGalleriesEntity gallery(long gid, String title) {
        EhGalleriesEntity gallery = new EhGalleriesEntity();
        gallery.setGid(gid);
        gallery.setTitle(title);
        gallery.setTags(List.of("artist:example"));
        return gallery;
    }
}
