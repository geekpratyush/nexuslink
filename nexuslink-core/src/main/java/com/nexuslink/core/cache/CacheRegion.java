package com.nexuslink.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * Typed Caffeine cache region with configurable TTL and max size.
 * Use {@link CacheRegistry} to obtain named instances.
 */
public final class CacheRegion<K, V> {

    private final Cache<K, V> cache;
    private final String name;

    private CacheRegion(String name, Duration ttl, int maxSize) {
        this.name = name;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .recordStats()
                .build();
    }

    public static <K, V> CacheRegion<K, V> of(String name, Duration ttl, int maxSize) {
        return new CacheRegion<>(name, ttl, maxSize);
    }

    public Optional<V> get(K key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    public V getOrLoad(K key, Function<K, V> loader) {
        return cache.get(key, loader);
    }

    public void put(K key, V value) {
        cache.put(key, value);
    }

    public void invalidate(K key) {
        cache.invalidate(key);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    public long estimatedSize() {
        return cache.estimatedSize();
    }

    public String name() {
        return name;
    }

    /** Hit ratio for monitoring/metrics. */
    public double hitRate() {
        return cache.stats().hitRate();
    }
}
