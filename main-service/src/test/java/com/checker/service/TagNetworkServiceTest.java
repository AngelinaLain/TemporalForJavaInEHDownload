package com.checker.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TagNetworkServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void allCatalogPagesUseOneImmutableSnapshotAndOneStreamingAggregateWithoutAnyPairQuery() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EhTagTranslationService translations = mock(EhTagTranslationService.class);
        when(translations.getTranslationMap()).thenReturn(Map.of("artist:tag-0", "译名"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < 1250; index++) rows.add(Map.of("name", "artist:tag-" + index, "value", 1300 - index));
        stubCatalogRows(jdbc, rows);
        TagNetworkService service = new TagNetworkService(jdbc, translations);
        var first = service.network(0, 2, null, 0, 500, null);
        String id = (String) first.get("snapshotId");
        assertNotNull(id);
        rows.clear(); // Later database changes must not alter ordering, counts or content of this snapshot.
        var second = service.network(0, 2, null, 500, 500, id);
        var last = service.network(0, 2, null, 1000, 500, id);
        assertEquals(1250, first.get("total"));
        assertEquals(1250, last.get("total"));
        assertEquals(id, second.get("snapshotId"));
        assertEquals(300, first.get("snapshotSeconds"));
        assertEquals(500, ((List<?>) first.get("nodes")).size());
        assertEquals("译名", ((List<Map<String, Object>>) first.get("nodes")).get(0).get("label"));
        assertEquals("artist:tag-500", ((List<Map<String, Object>>) second.get("nodes")).get(0).get("name"));
        assertEquals(250, ((List<?>) last.get("nodes")).size());
        assertEquals(false, last.get("hasMore"));
        assertEquals(1250, last.get("nextOffset"));
        assertTrue(((List<?>) first.get("links")).isEmpty());
        ArgumentCaptor<PreparedStatementCreator> creator = ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(jdbc, times(1)).query(creator.capture(), any(RowCallbackHandler.class));
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(statement);
        creator.getValue().createPreparedStatement(connection);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture(), eq(ResultSet.TYPE_FORWARD_ONLY), eq(ResultSet.CONCUR_READ_ONLY));
        verify(statement).setFetchSize(Integer.MIN_VALUE);
        assertEquals(1, sql.getValue().split("JSON_TABLE", -1).length - 1);
        assertFalse(sql.getValue().contains("LIMIT"), "all catalog must not silently truncate tags");
        verify(jdbc, never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }

    @Test
    void expiredOrMismatchedSnapshotsFailExplicitlyWithoutRequeryingTheDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicLong nanos = new AtomicLong();
        stubCatalogRows(jdbc, List.of(Map.of("name", "artist:focus", "value", 8)));
        TagNetworkService service = new TagNetworkService(jdbc, mock(EhTagTranslationService.class), nanos::get,
                64 * 1024 * 1024, 32 * 1024 * 1024);
        String id = (String) service.network(0, 2, " title ", 0, 500, null).get("snapshotId");
        assertThrows(IllegalArgumentException.class, () -> service.network(0, 2, "other", 0, 500, id));
        nanos.set(TimeUnit.SECONDS.toNanos(301));
        var failure = assertThrows(IllegalArgumentException.class, () -> service.network(0, 2, "title", 500, 500, id));
        assertTrue(failure.getMessage().contains("重新加载"));
        verify(jdbc, times(1)).query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));
        assertThrows(IllegalArgumentException.class, () -> service.network(0, 2, "title", 500, 500, null));
        verify(jdbc, times(1)).query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));
    }

    @Test
    void oversizedCatalogStopsReadingAndReturnsAnActionableErrorInsteadOfCreatingUncachedPages() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getString("name")).thenReturn("artist:large");
        doAnswer(call -> {
            RowCallbackHandler handler = call.getArgument(1);
            handler.processRow(row);
            fail("budget overflow must stop before the next row");
            return null;
        }).when(jdbc).query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));
        TagNetworkService service = new TagNetworkService(jdbc, mock(EhTagTranslationService.class),
                System::nanoTime, 1024, 400);
        var failure = assertThrows(IllegalArgumentException.class, () -> service.network(0, 2, null, 0, 500, null));
        assertTrue(failure.getMessage().contains("缩小作品关键词"));
        verify(row, never()).getLong(anyString());
    }

    @Test
    void focusQueriesOnlyOneExpandedTagArrayAndReportsTruncatedConnections() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EhTagTranslationService translations = mock(EhTagTranslationService.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(120L, 90L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("name", "group:friend", "value", 45)));
        TagNetworkService service = new TagNetworkService(jdbc, translations);
        var result = service.neighbors("artist:focus", "name%' OR 1=1", 3, 100);
        assertEquals(120L, result.get("totalConnections"));
        assertEquals(true, result.get("truncated"));
        assertEquals(2, ((List<?>) result.get("nodes")).size());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), args.capture());
        assertEquals(1, sql.getValue().split("JSON_TABLE", -1).length - 1);
        assertTrue(sql.getValue().contains("JSON_CONTAINS(g.tags,JSON_QUOTE(?))"));
        assertFalse(sql.getValue().contains("artist:focus"));
        assertFalse(sql.getValue().contains("OR 1=1"));
        assertEquals("artist:focus", args.getValue()[1]);
        assertEquals(100, args.getValue()[4]);
        service.neighbors("artist:focus", "name%' OR 1=1", 3, 100);
        verify(jdbc, times(1)).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void invalidInputsDoNotReachDatabaseAndOffsetBeyondSnapshotReturnsEmptyPage() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TagNetworkService service = new TagNetworkService(jdbc, mock(EhTagTranslationService.class));
        assertThrows(IllegalArgumentException.class, () -> service.neighbors("", null, 2, 100));
        assertThrows(IllegalArgumentException.class, () -> service.network(0, 2, "x".repeat(501), 0, 500, null));
        assertThrows(IllegalArgumentException.class, () -> service.network(0, 2, null, -1, 500, null));
        assertThrows(IllegalArgumentException.class, () -> service.network(0, 2, null, 500, 500, null));
        verifyNoInteractions(jdbc);
        stubCatalogRows(jdbc, List.of());
        String id = (String) service.network(0, 2, null, 0, 500, null).get("snapshotId");
        var result = service.network(0, 2, null, Integer.MAX_VALUE, 500, id);
        assertEquals(0, result.get("total"));
        assertEquals(false, result.get("hasMore"));
    }

    private static void stubCatalogRows(JdbcTemplate jdbc, List<Map<String, Object>> rows) {
        doAnswer(call -> {
            RowCallbackHandler callback = call.getArgument(1);
            for (Map<String, Object> item : rows) {
                ResultSet result = mock(ResultSet.class);
                when(result.getString("name")).thenReturn((String) item.get("name"));
                when(result.getLong("value")).thenReturn(((Number) item.get("value")).longValue());
                callback.processRow(result);
            }
            return null;
        }).when(jdbc).query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));
    }
}
