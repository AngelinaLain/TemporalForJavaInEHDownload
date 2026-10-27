package com.checker.service;

import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import java.util.function.ToLongFunction;

/** 进程内的小型最近使用缓存：限制有效期和总大小，并合并同一键的同时加载请求。 */
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

    /** 优先返回未过期结果；同键未命中时合并加载，避免多个图片请求同时重复读取同一份内容。 */
    V getOrLoad(K key, Callable<V> loader) throws Exception {
        V cached = get(key);
        if (cached != null) return cached;
        // 每个缓存使用自己的分段锁，加载时访问另一个缓存不会争用同一组锁。
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

    /** 移除指定键，主动重新检测时用于绕过旧的成功或失败结果。 */
    void invalidate(K key) {
        // 等正在加载的同一键结束后再删除，避免主动重试刚清完缓存，旧请求又把旧值放回来。
        synchronized (locks[(key.hashCode() & Integer.MAX_VALUE) % locks.length]) {
            synchronized (this) {
                Entry<V> removed = entries.remove(key);
                if (removed != null) weight -= removed.weight();
            }
        }
    }

    /** 按大小计算缓存权重，超出预算时淘汰最久未使用的条目。 */
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
