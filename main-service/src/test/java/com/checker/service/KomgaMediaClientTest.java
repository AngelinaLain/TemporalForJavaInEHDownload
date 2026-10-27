package com.checker.service;

import com.checker.config.EhNetworkConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class KomgaMediaClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private EhNetworkConfig config;
    private KomgaMediaClient client;
    private Handler handler;
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Call call = new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(), exchange.getRequestHeaders().getFirst("X-API-Key"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            calls.add(call);
            try { handler.respond(exchange, call); }
            catch (Exception failure) { exchange.close(); }
        });
        server.start();
        config = new EhNetworkConfig();
        config.getKomga().setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/komga/?old=secret#old");
        config.getKomga().setApiKey("private-test-key");
        config.getKomga().setLibraryId("target-library");
        client = new KomgaMediaClient(config, JSON);
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void findsTheUniqueRescannedPhysicalBookAcrossPagesAndKeepsContextAndLibraryFilter() throws Exception {
        handler = (exchange, call) -> {
            if (call.query().contains("page=0")) {
                reply(exchange, 200, Map.of("content", List.of(
                        book("similar", "[15530360] Other.cbz", "target-library", 8),
                        book("wrong-library", "[1553036] Exact.cbz", "other-library", 5),
                        Map.of("id", "title-only", "name", "Other.cbz", "metadata", Map.of("title", "[1553036] Exact"))
                ), "number", 0, "last", false, "totalElements", 4, "totalPages", 2));
            } else {
                reply(exchange, 200, Map.of("content", List.of(Map.of("id", "new-book-id", "name", "",
                        "url", "file:///library/%5B1553036%5D%20%E6%96%B0%E6%A0%87%E9%A2%98.cbz",
                        "libraryId", "target-library", "media", Map.of("pagesCount", 78))),
                        "number", 1, "last", true, "totalElements", 4, "totalPages", 2));
            }
        };
        var found = client.findBook(1553036L, "[1553036] Historical title.zip").orElseThrow();
        assertEquals("new-book-id", found.bookId());
        assertEquals(78, found.pageCount());
        assertEquals(2, calls.size());
        for (Call call : calls) {
            assertEquals("POST", call.method());
            assertEquals("/komga/api/v1/books/list", call.path());
            assertTrue(call.query().contains("size=100"));
            assertFalse(call.query().contains("old=secret"));
            assertEquals("private-test-key", call.key());
            JsonNode body = JSON.readTree(call.body());
            assertEquals("1553036", body.path("fullTextSearch").asText());
            assertEquals("is", body.path("condition").path("libraryId").path("operator").asText());
            assertEquals("target-library", body.path("condition").path("libraryId").path("value").asText());
        }
    }

    @Test void rejectsAmbiguousCandidatesRatherThanSelectingTheFirstSearchResult() {
        handler = (exchange, call) -> reply(exchange, 200, Map.of("content", List.of(
                book("first", "[42] One.cbz", "target-library", 10),
                book("second", "[42] Another.cbz", "target-library", 20)), "last", true));
        IOException failure = assertThrows(IOException.class, () -> client.findBook(42L, "stale.zip"));
        assertTrue(failure.getMessage().contains("多个"));
        assertFalse(failure.getMessage().contains("private-test-key"));
    }

    @Test void doesNotClaimUniquenessWhenSearchExceedsItsPageBudget() {
        handler = (exchange, call) -> {
            int page = Integer.parseInt(call.query().replaceFirst(".*page=(\\d+).*", "$1"));
            reply(exchange, 200, Map.of("content", List.of(book("candidate-" + page,
                    page == 0 ? "[42] Candidate.cbz" : "[420] Other.cbz", "target-library", 10)),
                    "number", page, "last", false));
        };
        IOException failure = assertThrows(IOException.class, () -> client.findBook(42L, null));
        assertTrue(failure.getMessage().contains("检索范围"));
        assertEquals(10, calls.size());
    }

    @Test void handlesMissingPageCountsAndLegacyExactFilenamesWithoutMatchingMetadataTitles() throws Exception {
        handler = (exchange, call) -> {
            if (call.method().equals("GET")) {
                reply(exchange, 200, Map.of("media", Map.of("pagesCount", 23)));
            } else if (JSON.readTree(call.body()).path("fullTextSearch").asText().equals("42")) {
                reply(exchange, 200, Map.of("content", List.of(Map.of("id", "title-only", "name", "Other",
                        "metadata", Map.of("title", "42 legacy title"))), "last", true));
            } else {
                reply(exchange, 200, Map.of("content", List.of(Map.of("id", "legacy", "name", "Legacy + title.zip")), "last", true));
            }
        };
        var found = client.findBook(42L, "Legacy + title.cbz").orElseThrow();
        assertEquals("legacy", found.bookId());
        assertEquals(23, found.pageCount());
        assertEquals(3, calls.size());
        assertEquals("Legacy title", JSON.readTree(calls.get(1).body()).path("fullTextSearch").asText());
        assertEquals("/komga/api/v1/books/legacy", calls.get(2).path());
    }

    @Test void distinguishesKomgaAccessErrorsAndMissingIdsWithoutLeakingCredentials() {
        handler = (exchange, call) -> reply(exchange, 401, Map.of("secret", "private-test-key"));
        var denied = assertThrows(KomgaMediaClient.KomgaHttpException.class, () -> client.findBook(42L, null));
        assertEquals(401, denied.statusCode());
        assertFalse(denied.isNotFound());
        assertTrue(denied.getMessage().contains("Komga"));
        assertTrue(denied.getMessage().contains("凭据"));
        assertFalse(denied.getMessage().contains("private-test-key"));
        handler = (exchange, call) -> reply(exchange, 404, Map.of());
        var missing = assertThrows(KomgaMediaClient.KomgaHttpException.class, () -> client.pageCount("old-book-id"));
        assertEquals(404, missing.statusCode());
        assertTrue(missing.isNotFound());
    }

    @Test void titleKeywordsRetrieveCandidatesButOnlyPhysicalGidIdentityCanSelectARescannedBook() throws Exception {
        handler = (exchange, call) -> {
            String term = JSON.readTree(call.body()).path("fullTextSearch").asText();
            if (term.equals("1553036")) {
                reply(exchange, 200, Map.of("content", List.of(), "last", true));
            } else {
                reply(exchange, 200, Map.of("content", List.of(
                        book("wrong-gid", "[1553037] Jishin Kajou.cbz", "target-library", 9),
                        book("rescanned", "[1553036] Renamed archive.cbz", "target-library", 11),
                        Map.of("id", "same-title", "name", "Unrelated.cbz", "metadata", Map.of("title", "Jishin Kajou"))
                ), "last", true));
            }
        };
        var book = client.findBook(1553036L,
                "[1553036] [Akaishi Shiroishi] Jishin Kajou Loli Title [Chinese].cbz").orElseThrow();
        assertEquals("rescanned", book.bookId());
        assertEquals(11, book.pageCount());
        assertEquals(2, calls.size());
        assertEquals("Jishin Kajou", JSON.readTree(calls.get(1).body()).path("fullTextSearch").asText());
    }

    @Test void returnsEmptyOnlyForACompleteNonMatchingSearchAndRejectsIncompletePagination() throws Exception {
        handler = (exchange, call) -> reply(exchange, 200, Map.of("content", List.of(), "last", true));
        assertTrue(client.findBook(42L, null).isEmpty());
        handler = (exchange, call) -> reply(exchange, 200, Map.of("content", List.of(book("maybe", "[42] One.cbz", "target-library", 1))));
        IOException failure = assertThrows(IOException.class, () -> client.findBook(42L, null));
        assertTrue(failure.getMessage().contains("分页完成信息"));
    }

    @Test void completeLibraryInventoryReadsOnlyMetadataAndNormalizesPhysicalNamesAcrossAllPages() throws Exception {
        handler = (exchange, call) -> {
            if (call.query().contains("page=0")) reply(exchange, 200, Map.of("content", List.of(
                    Map.of("id", "new-id", "name", "[42] File", "url", "/library/%5B42%5D%20File.cbz", "libraryId", "target-library")),
                    "number", 0, "last", false, "totalElements", 2, "totalPages", 2));
            else reply(exchange, 200, Map.of("content", List.of(Map.of("id", "deleted-id", "deleted", true)),
                    "number", 1, "last", true, "totalElements", 2, "totalPages", 2));
        };
        var inventory = client.librarySnapshot(() -> false);
        assertEquals(1, inventory.total());
        assertEquals("new-id", inventory.books().get(0).bookId());
        assertEquals(List.of("[42] File", "[42] File.cbz"), inventory.books().get(0).filenames());
        assertEquals(2, calls.size());
        for (Call call : calls) {
            assertEquals("POST", call.method());
            assertEquals("/komga/api/v1/books/list", call.path());
            assertEquals("private-test-key", call.key());
            assertFalse(call.body().contains("fullTextSearch"), "batch reconciliation must read the complete physical inventory");
            assertEquals("target-library", JSON.readTree(call.body()).path("condition").path("libraryId").path("value").asText());
        }
    }

    @Test void libraryInventoryRejectsChangingTotalsAndDuplicateIdsInsteadOfReturningPartialData() {
        handler = (exchange, call) -> {
            int page = call.query().contains("page=0") ? 0 : 1;
            reply(exchange, 200, Map.of("content", List.of(book("id-" + page, "[42] File", "target-library", 1)),
                    "number", page, "last", page == 1, "totalElements", page == 0 ? 2 : 3));
        };
        assertTrue(assertThrows(IOException.class, () -> client.librarySnapshot(() -> false)).getMessage().contains("发生变化"));
        handler = (exchange, call) -> reply(exchange, 200, Map.of("content", List.of(
                book("same", "[42] A", "target-library", 1), book("same", "[43] B", "target-library", 1)),
                "last", true, "totalElements", 2));
        assertTrue(assertThrows(IOException.class, () -> client.librarySnapshot(() -> false)).getMessage().contains("重复"));
    }

    @Test void cancellationStopsTheLibraryInventoryWithoutFetchingAnotherPage() {
        assertThrows(CancellationException.class, () -> client.librarySnapshot(() -> true));
        assertTrue(calls.isEmpty());
        AtomicBoolean cancelled = new AtomicBoolean();
        handler = (exchange, call) -> {
            cancelled.set(true);
            reply(exchange, 200, Map.of("content", List.of(book("one", "[42] File", "target-library", 1)), "last", false));
        };
        assertThrows(CancellationException.class, () -> client.librarySnapshot(cancelled::get));
        assertEquals(1, calls.size());
    }

    @Test void libraryInventoryRejectsUnknownCompletionOversizeAndWrongLibrary() {
        handler = (exchange, call) -> reply(exchange, 200, Map.of("content", List.of()));
        assertTrue(assertThrows(IOException.class, () -> client.librarySnapshot(() -> false)).getMessage().contains("分页完成"));
        handler = (exchange, call) -> reply(exchange, 200, Map.of("content", List.of(), "last", true, "totalElements", 50_001));
        assertTrue(assertThrows(IOException.class, () -> client.librarySnapshot(() -> false)).getMessage().contains("50000"));
        handler = (exchange, call) -> reply(exchange, 200, Map.of("content", List.of(book("wrong", "[42] File", "other-library", 1)), "last", true));
        assertTrue(assertThrows(IOException.class, () -> client.librarySnapshot(() -> false)).getMessage().contains("Library"));
    }

    private static Map<String, Object> book(String id, String name, String library, int pages) {
        return Map.of("id", id, "name", name, "libraryId", library, "media", Map.of("pagesCount", pages));
    }
    private static void reply(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    private record Call(String method, String path, String query, String key, String body) {}
    @FunctionalInterface private interface Handler { void respond(HttpExchange exchange, Call call) throws Exception; }
}
