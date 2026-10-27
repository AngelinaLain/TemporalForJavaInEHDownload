package com.checker.service;

import com.checker.dto.GallerySearchRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GalleryQueryServiceTest {
    @Test
    void combinesQuotedKeywordsTagsAndDateBoundsWithoutInterpolatingInput() {
        GallerySearchRequest query = new GallerySearchRequest();
        query.setKeyword("\"my story\" name%' OR 1=1");
        query.setKeywordMode("all");
        query.setTags("[\"artist:name, with comma\",\"language:chinese\"]");
        query.setExcludeTags("[\"other:excluded\"]");
        query.setFrom("2026-01-01");
        query.setTo("2026-01-31");
        query.setCollectionId(9L);
        GalleryFilterBuilder.SqlFilter filter = GalleryFilterBuilder.build(query);
        assertFalse(filter.where().contains("my story"));
        assertFalse(filter.where().contains("OR 1=1"));
        assertFalse(filter.where().contains("artist:name"));
        assertTrue(filter.where().contains("EXISTS"));
        assertTrue(filter.where().contains("g.crawled_at < ?"));
        assertTrue(filter.args().contains("%my story%"));
        assertTrue(filter.args().contains("artist:name, with comma"));
        assertTrue(filter.args().contains("2026-02-01"));
    }

    @Test
    void rejectsInvalidSearchRangesAndTagPayloadsBeforeQueryingDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        GalleryQueryService service = new GalleryQueryService(jdbc);
        GallerySearchRequest query = new GallerySearchRequest();
        query.setMinRating(4D);
        query.setMaxRating(2D);
        assertThrows(IllegalArgumentException.class, () -> service.search(query));
        query.setMinRating(null); query.setMaxRating(null);
        query.setFrom("2026-02-30");
        assertThrows(IllegalArgumentException.class, () -> service.search(query));
        query.setFrom(null); query.setTags("[1]");
        assertThrows(IllegalArgumentException.class, () -> service.search(query));
        query.setTags(null); query.setGid("-7");
        assertThrows(IllegalArgumentException.class, () -> service.search(query));
        query.setGid(null); query.setSortBy("rating; DROP TABLE eh_galleries");
        assertThrows(IllegalArgumentException.class, () -> service.search(query));
        verifyNoInteractions(jdbc);
    }

    @Test
    void projectsOnlyCurrentPageAndEnrichesCollectionsInOneBatch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("gid", 11L); first.put("title", "One"); first.put("download_status", "已入库"); first.put("tags", "[\"artist:example\"]");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("gid", 12L); second.put("title", "Two"); second.put("download_status", "PENDING"); second.put("tags", null);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(
                List.of(first, second), List.of(Map.of("gid", 11L, "id", 3L, "name", "Series")));
        GallerySearchRequest query = new GallerySearchRequest();
        query.setSize(1000);
        var result = new GalleryQueryService(jdbc).search(query);
        assertEquals(100, result.getSize());
        assertEquals("SERIES", result.getRecords().get(0).get("collectionType"));
        assertEquals("Series", result.getRecords().get(0).get("collectionName"));
        assertEquals("IMPORTED", result.getRecords().get(0).get("downloadStatus"));
        assertEquals(List.of("artist:example"), result.getRecords().get(0).get("tags"));
        assertEquals("STANDALONE", result.getRecords().get(1).get("collectionType"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).queryForList(sql.capture(), any(Object[].class));
        assertFalse(sql.getAllValues().get(0).contains("_trace_"));
        assertFalse(sql.getAllValues().get(0).contains("g.token"));
        assertTrue(sql.getAllValues().get(0).contains("LIMIT ? OFFSET ?"));
        assertTrue(sql.getAllValues().get(1).contains("ci.gid IN (?,?)"));
    }

    @Test
    void zeroSizePageDoesNotRunAnyEntityOrMembershipQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        var result = new GalleryQueryService(jdbc).search(new GallerySearchRequest());
        assertEquals(0, result.getTotal());
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }
}
