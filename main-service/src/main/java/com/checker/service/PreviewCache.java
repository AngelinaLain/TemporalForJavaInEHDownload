package com.checker.service;

import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import java.util.function.ToLongFunction;

/** Small process-local LRU with TTL, byte budgets and per-key request coalescing. */
final class PreviewCache<K, V> {
    private record Entry<V>(V value, long expiresAt, long weight) {}
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, .75f, true);
    private final Object[] locks = new Object[16];
    private final int maxEntries;
    private final long maxWeight;
    private final long ttlMillis;
    private final ToLongFunction<V> weigh;
    private long weight;

    PreviewCache(int maxEntries, long maxWeight, long ttlMillis, ToLongFunction<V> weigh) {
        this.maxEntries = maxEntries;
        this.maxWeight = maxWeight;
        this.ttlMillis = ttlMillis;
        this.weigh = weigh;
        for (int index = 0; index < locks.length; index++) locks[index] = new Object();
    }

    V getOrLoad(K key, Callable<V> loader) throws Exception {
        V cached = get(key);
        if (cached != null) return cached;
        // Each cache owns its own locks; loaders can safely consult downstream caches.
        synchronized (locks[(key.hashCode() & Integer.MAX_VALUE) % locks.length]) {
            cached = get(key);
            if (cached != null) return cached;
            V value = loader.call();
            if (value != null) put(key, value);
            return value;
        }
    }

    private synchronized V get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) return null;
        if (entry.expiresAt() <= System.currentTimeMillis()) {
            entries.remove(key);
            weight -= entry.weight();
            return null;
        }
        return entry.value();
    }

    void invalidate(K key) {
        // Wait for an in-flight load before removing it, so an explicit retry cannot resurrect stale data.
        synchronized (locks[(key.hashCode() & Integer.MAX_VALUE) % locks.length]) {
            synchronized (this) {
                Entry<V> removed = entries.remove(key);
                if (removed != null) weight -= removed.weight();
            }
        }
    }

    private synchronized void put(K key, V value) {
        long itemWeight = Math.max(1, weigh.applyAsLong(value));
        if (itemWeight > maxWeight) return;
        Entry<V> previous = entries.put(key, new Entry<>(value, System.currentTimeMillis() + ttlMillis, itemWeight));
        weight += itemWeight - (previous == null ? 0 : previous.weight());
        while (entries.size() > maxEntries || weight > maxWeight) {
            K oldest = entries.keySet().iterator().next();
            weight -= entries.remove(oldest).weight();
        }
    }
}
