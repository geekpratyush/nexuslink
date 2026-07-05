package com.nexuslink.core.net;

import com.nexuslink.core.cache.CacheRegion;
import com.nexuslink.core.cache.CacheRegistry;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Short-TTL DNS resolution cache (default 30s, matching {@link CacheRegistry#DNS}).
 *
 * <p>Wraps a {@link CacheRegion} so repeated lookups of the same host within the TTL
 * window skip the underlying resolver. The resolver is injectable so the cache can be
 * exercised without touching real DNS. A host that fails to resolve is <em>not</em>
 * cached — the next lookup retries.
 */
public final class DnsCache {

    /** Resolver seam: host name → its addresses (never empty on success). */
    @FunctionalInterface
    public interface Resolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    /** The JVM's built-in resolver ({@link InetAddress#getAllByName}). */
    public static final Resolver SYSTEM = host -> List.of(InetAddress.getAllByName(host));

    private final CacheRegion<String, List<InetAddress>> region;
    private final Resolver resolver;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public DnsCache(CacheRegion<String, List<InetAddress>> region, Resolver resolver) {
        this.region = Objects.requireNonNull(region, "region");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /** Standard cache backed by the shared {@link CacheRegistry#DNS} region and system DNS. */
    public static DnsCache standard() {
        return new DnsCache(CacheRegistry.get().region(CacheRegistry.DNS), SYSTEM);
    }

    /** Standalone cache with its own private region and TTL (handy for tests / isolation). */
    public static DnsCache withTtl(Duration ttl, Resolver resolver) {
        return new DnsCache(CacheRegion.of("dns-adhoc", ttl, 500), resolver);
    }

    /**
     * Resolve {@code host}, returning cached addresses when available.
     *
     * @throws UnknownHostException if the (uncached) resolver cannot resolve the host
     * @throws IllegalArgumentException if {@code host} is null or blank
     */
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        String key = host.trim();
        List<InetAddress> cached = region.get(key).orElse(null);
        if (cached != null) {
            hits.incrementAndGet();
            return cached;
        }
        misses.incrementAndGet();
        List<InetAddress> resolved = resolver.resolve(key);
        if (resolved == null || resolved.isEmpty()) {
            throw new UnknownHostException(key);
        }
        List<InetAddress> immutable = List.copyOf(resolved);
        region.put(key, immutable);
        return immutable;
    }

    /** First resolved address for {@code host} (convenience for connect paths). */
    public InetAddress resolveFirst(String host) throws UnknownHostException {
        return resolve(host).get(0);
    }

    /** Drop any cached entry for {@code host} so the next lookup re-resolves. */
    public void invalidate(String host) {
        if (host != null && !host.isBlank()) region.invalidate(host.trim());
    }

    public long hitCount() {
        return hits.get();
    }

    public long missCount() {
        return misses.get();
    }

    /** Local hit ratio in [0,1]; 0 when no lookups have been made yet. */
    public double hitRate() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0.0 : (double) hits.get() / total;
    }
}
