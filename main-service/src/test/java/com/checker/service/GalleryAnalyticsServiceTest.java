package com.checker.service;

import com.checker.dto.GalleryAnalyticsRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GalleryAnalyticsServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void zeroFillsTimelinePreservesExactRangeAndCachesIdenticalFilters() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("total", 3L); overview.put("size_mb", 12D); overview.put("avg_rating", 4D); overview.put("avg_pages", 10D);
        overview.put("first_date", "2026-01-03");
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(overview);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("SELECT DATE_FORMAT(g.crawled_at"))
                return List.of(new LinkedHashMap<>(Map.of("date", "2026-01-03", "value", 3L, "size_mb", 12D)));
            return List.of();
        });
        GalleryAnalyticsRequest request = new GalleryAnalyticsRequest();
        request.setFrom("2026-01-01"); request.setTo("2026-01-07");
        GalleryAnalyticsService service = new GalleryAnalyticsService(jdbc);
        Map<String, Object> data = service.analyze(request);
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) data.get("timeline");
        assertEquals(7, timeline.size());
        assertEquals(0L, timeline.get(0).get("value"));
        assertEquals(3L, timeline.get(2).get("value"));
        assertEquals(Map.of("from", "2026-01-03", "to", "2026-01-03"), timeline.get(2).get("filter"));
        assertEquals(Map.of("from", "2026-01-01", "to", "2026-01-07", "grain", "day"), data.get("range"));
        assertSame(data, service.analyze(request));
        verify(jdbc, times(1)).queryForMap(anyString(), any(Object[].class));
        verify(jdbc, times(5)).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void rejectsUnknownGrainAndInvertedDatesWithoutQueries() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        GalleryAnalyticsService service = new GalleryAnalyticsService(jdbc);
        GalleryAnalyticsRequest request = new GalleryAnalyticsRequest();
        request.setGrain("arbitrary SQL");
        assertThrows(IllegalArgumentException.class, () -> service.analyze(request));
        request.setGrain("month"); request.setFrom("2026-02-01"); request.setTo("2026-01-01");
        assertThrows(IllegalArgumentException.class, () -> service.analyze(request));
        verifyNoInteractions(jdbc);
    }
}
