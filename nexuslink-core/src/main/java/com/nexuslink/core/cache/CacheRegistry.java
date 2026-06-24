package com.nexuslink.core.cache;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for all named cache regions.
 * Pre-defined regions match the cache strategy in TASKS.md.
 */
public final class CacheRegistry {

    // Pre-defined region names
    public static final String DNS                  = "dns";
    public static final String TLS_SESSION          = "tls-session";
    public static final String KAFKA_TOPICS         = "kafka-topics";
    public static final String SCHEMA_REGISTRY      = "schema-registry";
    public static final String CONSUMER_LAG         = "consumer-lag";
    public static final String HELP_SEARCH          = "help-search";
    public static final String HISTORY_RECENT       = "history-recent";
    public static final String OAUTH_TOKENS         = "oauth-tokens";
    public static final String JDBC_SCHEMA          = "jdbc-schema";
    public static final String LDAP_RESULTS         = "ldap-results";

    private static final CacheRegistry INSTANCE = new CacheRegistry();
    private final Map<String, CacheRegion<?, ?>> regions = new ConcurrentHashMap<>();

    private CacheRegistry() {
        // Register all standard regions
        register(CacheRegion.of(DNS,             Duration.ofSeconds(30),  500));
        register(CacheRegion.of(TLS_SESSION,     Duration.ofSeconds(300), 200));
        register(CacheRegion.of(KAFKA_TOPICS,    Duration.ofSeconds(30),  1000));
        register(CacheRegion.of(SCHEMA_REGISTRY, Duration.ofSeconds(60),  500));
        register(CacheRegion.of(CONSUMER_LAG,    Duration.ofSeconds(5),   200));
        register(CacheRegion.of(HELP_SEARCH,     Duration.ofHours(24),    200));
        register(CacheRegion.of(HISTORY_RECENT,  Duration.ofHours(1),     100));
        register(CacheRegion.of(OAUTH_TOKENS,    Duration.ofHours(1),     100));
        register(CacheRegion.of(JDBC_SCHEMA,     Duration.ofSeconds(120), 100));
        register(CacheRegion.of(LDAP_RESULTS,    Duration.ofSeconds(30),  200));
    }

    public static CacheRegistry get() {
        return INSTANCE;
    }

    private void register(CacheRegion<?, ?> region) {
        regions.put(region.name(), region);
    }

    @SuppressWarnings("unchecked")
    public <K, V> CacheRegion<K, V> region(String name) {
        CacheRegion<?, ?> region = regions.get(name);
        if (region == null) throw new IllegalArgumentException("Unknown cache region: " + name);
        return (CacheRegion<K, V>) region;
    }

    /** Register a custom cache region (e.g., from a plugin). */
    public <K, V> CacheRegion<K, V> registerCustom(String name, java.time.Duration ttl, int maxSize) {
        CacheRegion<K, V> region = CacheRegion.of(name, ttl, maxSize);
        regions.put(name, region);
        return region;
    }
}
