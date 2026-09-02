package com.checker.temporalServices.activities.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KomgaBookMatcherTest {

    @Test
    void selectsOnlyExactFilenameInExpectedSeriesAndLibrary() {
        JSONArray content = new JSONArray()
                .set(book("correct", "[123456] Title.cbz", "series-1", "library-1"))
                .set(book("wrong-file", "[1234567] Title.cbz", "series-1", "library-1"))
                .set(book("wrong-series", "[123456] Title.cbz", "series-2", "library-1"))
                .set(book("wrong-library", "[123456] Title.cbz", "series-1", "library-2"));

        List<String> result = KomgaBookMatcher.exactBookIds(
                content, 123456L, "[123456] Title.cbz", "series-1", "library-1");

        assertEquals(List.of("correct"), result);
    }

    @Test
    void acceptsKomgaNameWithoutArchiveExtensionAndEncodedUrl() {
        JSONObject book = book("book-1", "", "series-1", "library-1")
                .set("url", "file:///library/%5B123456%5D%20%E4%B8%AD%E6%96%87%E6%A0%87%E9%A2%98.cbz")
                .set("name", "[123456] 中文标题");

        List<String> result = KomgaBookMatcher.exactBookIds(
                new JSONArray().set(book), 123456L, "[123456] 中文标题.cbz", "series-1", "library-1");

        assertEquals(List.of("book-1"), result);
    }

    @Test
    void fallbackGidPrefixUsesBoundaries() {
        JSONArray content = new JSONArray()
                .set(book("similar", "[1234567] Other.cbz", "series-1", "library-1"))
                .set(book("exact", "[123456] Target.cbz", "series-1", "library-1"));

        List<String> result = KomgaBookMatcher.exactBookIds(
                content, 123456L, null, "series-1", "library-1");

        assertEquals(List.of("exact"), result);
    }

    @Test
    void returnsAllExactMatchesSoCallerCanRejectAmbiguity() {
        JSONArray content = new JSONArray()
                .set(book("book-1", "[123456] Title.cbz", "series-1", "library-1"))
                .set(book("book-2", "[123456] Title.cbz", "series-1", "library-1"));

        List<String> result = KomgaBookMatcher.exactBookIds(
                content, 123456L, "[123456] Title.cbz", "series-1", "library-1");

        assertEquals(List.of("book-1", "book-2"), result);
    }

    @Test
    void emptyContentHasNoMatch() {
        assertTrue(KomgaBookMatcher.exactBookIds(
                new JSONArray(), 123456L, "[123456] Title.cbz", "series-1", "library-1").isEmpty());
    }

    private static JSONObject book(String id, String name, String seriesId, String libraryId) {
        return new JSONObject()
                .set("id", id)
                .set("name", name)
                .set("seriesId", seriesId)
                .set("libraryId", libraryId);
    }
}
