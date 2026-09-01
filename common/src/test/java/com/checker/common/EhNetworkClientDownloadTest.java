package com.checker.common;

import com.checker.config.EhNetworkConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EhNetworkClientDownloadTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private EhNetworkClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();

        EhNetworkConfig config = new EhNetworkConfig();
        config.getRateLimit().setEnabled(false);
        client = new EhNetworkClient();
        ReflectionTestUtils.setField(client, "netConfig", config);
        client.init();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void resumesFromExistingPartWithValidatedContentRange() throws Exception {
        byte[] content = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> requestedRange = new AtomicReference<>();
        server.createContext("/resume", exchange -> {
            requestedRange.set(exchange.getRequestHeaders().getFirst("Range"));
            int start = Integer.parseInt(requestedRange.get().substring("bytes=".length(), requestedRange.get().length() - 1));
            byte[] remainder = java.util.Arrays.copyOfRange(content, start, content.length);
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + (content.length - 1) + "/" + content.length);
            exchange.getResponseHeaders().add("ETag", "\"archive-v1\"");
            send(exchange, 206, remainder);
        });

        Path target = tempDir.resolve("resume.zip");
        Files.write(target.resolveSibling("resume.zip.part"), java.util.Arrays.copyOf(content, 9));

        long bytes = client.downloadWithResume(url("/resume"), target);

        assertEquals("bytes=9-", requestedRange.get());
        assertEquals(content.length, bytes);
        assertArrayEquals(content, Files.readAllBytes(target));
    }

    @Test
    void truncatesOldPartWhenServerIgnoresRange() throws Exception {
        byte[] content = "complete replacement archive".getBytes(StandardCharsets.UTF_8);
        server.createContext("/full", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            send(exchange, 200, content);
        });
        Path target = tempDir.resolve("full.zip");
        Files.writeString(target.resolveSibling("full.zip.part"), "stale-prefix");

        client.downloadWithResume(url("/full"), target);

        assertArrayEquals(content, Files.readAllBytes(target));
    }

    @Test
    void resetsPartWhen416TotalDoesNotMatchLocalSize() throws Exception {
        byte[] content = "fresh archive content".getBytes(StandardCharsets.UTF_8);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/range-reset", exchange -> {
            if (calls.getAndIncrement() == 0) {
                exchange.getResponseHeaders().add("Content-Range", "bytes */" + content.length);
                exchange.sendResponseHeaders(416, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().add("Content-Range", "bytes 0-" + (content.length - 1) + "/" + content.length);
            send(exchange, 206, content);
        });
        Path target = tempDir.resolve("reset.zip");
        Files.writeString(target.resolveSibling("reset.zip.part"), "cache-that-is-too-long-for-the-new-file");

        client.downloadWithResume(url("/range-reset"), target);

        assertEquals(2, calls.get());
        assertArrayEquals(content, Files.readAllBytes(target));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
