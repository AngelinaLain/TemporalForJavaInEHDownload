package com.checker.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PreviewCacheTest {
    @Test
    void weightBudgetEvictsLeastRecentlyUsedItemsAndDoesNotCacheOversizeValues() throws Exception {
        PreviewCache<String, String> cache = new PreviewCache<>(4, 6, 60_000, String::length);
        AtomicInteger loads = new AtomicInteger();
        cache.getOrLoad("a", () -> "aa");
        cache.getOrLoad("b", () -> "bb");
        cache.getOrLoad("a", () -> { fail("cache hit should not load"); return ""; });
        cache.getOrLoad("c", () -> "cccc");
        assertEquals("aa", cache.getOrLoad("a", () -> { fail("recent item must survive"); return ""; }));
        assertEquals("reloaded", cache.getOrLoad("b", () -> { loads.incrementAndGet(); return "reloaded"; }));
        assertEquals("reloaded", cache.getOrLoad("b", () -> { loads.incrementAndGet(); return "reloaded"; }));
        assertEquals(2, loads.get(), "values larger than the cache byte budget must not be retained");
    }

    @Test
    void zeroTtlExpiresAndLoaderFailuresAreNotRetained() throws Exception {
        PreviewCache<String, String> immediate = new PreviewCache<>(4, 50, 0, String::length);
        assertEquals("first", immediate.getOrLoad("a", () -> "first"));
        assertEquals("second", immediate.getOrLoad("a", () -> "second"));
        PreviewCache<String, String> cache = new PreviewCache<>(4, 50, 60_000, String::length);
        assertThrows(IOException.class, () -> cache.getOrLoad("a", () -> { throw new IOException("unavailable"); }));
        assertEquals("recovered", cache.getOrLoad("a", () -> "recovered"));
    }

    @Test
    void concurrentRequestsForTheSameKeyAreCoalesced() throws Exception {
        PreviewCache<String, String> cache = new PreviewCache<>(4, 50, 60_000, String::length);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(4), start = new CountDownLatch(1), enteredLoader = new CountDownLatch(1), releaseLoader = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) results.add(pool.submit(() -> {
                ready.countDown(); assertTrue(start.await(3, TimeUnit.SECONDS));
                return cache.getOrLoad("same", () -> {
                    loads.incrementAndGet(); enteredLoader.countDown();
                    assertTrue(releaseLoader.await(3, TimeUnit.SECONDS));
                    return "preview";
                });
            }));
            assertTrue(ready.await(3, TimeUnit.SECONDS)); start.countDown();
            assertTrue(enteredLoader.await(3, TimeUnit.SECONDS)); releaseLoader.countDown();
            for (Future<String> result : results) assertEquals("preview", result.get(3, TimeUnit.SECONDS));
            assertEquals(1, loads.get());
        } finally {
            start.countDown(); releaseLoader.countDown(); pool.shutdownNow();
        }
    }
}
