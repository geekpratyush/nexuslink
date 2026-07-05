package com.nexuslink.core.net;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DnsCacheTest {

    private static InetAddress addr(int a, int b, int c, int d) {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void secondLookupIsServedFromCache() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DnsCache dns = DnsCache.withTtl(Duration.ofMinutes(1), host -> {
            calls.incrementAndGet();
            return List.of(addr(10, 0, 0, 1));
        });

        assertEquals(List.of(addr(10, 0, 0, 1)), dns.resolve("example.com"));
        assertEquals(List.of(addr(10, 0, 0, 1)), dns.resolve("example.com"));

        assertEquals(1, calls.get(), "resolver hit once; second lookup cached");
        assertEquals(1, dns.hitCount());
        assertEquals(1, dns.missCount());
        assertEquals(0.5, dns.hitRate(), 1e-9);
    }

    @Test
    void hostIsTrimmedForCacheKey() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DnsCache dns = DnsCache.withTtl(Duration.ofMinutes(1), host -> {
            calls.incrementAndGet();
            return List.of(addr(1, 2, 3, 4));
        });
        dns.resolve("host.local");
        dns.resolve("  host.local  ");
        assertEquals(1, calls.get());
    }

    @Test
    void resolveFirstReturnsLeadingAddress() throws Exception {
        DnsCache dns = DnsCache.withTtl(Duration.ofMinutes(1),
                host -> List.of(addr(9, 9, 9, 9), addr(8, 8, 8, 8)));
        assertEquals(addr(9, 9, 9, 9), dns.resolveFirst("multi.local"));
    }

    @Test
    void blankHostRejected() {
        DnsCache dns = DnsCache.withTtl(Duration.ofMinutes(1), host -> List.of(addr(1, 1, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> dns.resolve(" "));
        assertThrows(IllegalArgumentException.class, () -> dns.resolve(null));
    }

    @Test
    void emptyResolutionThrowsUnknownHost() {
        DnsCache dns = DnsCache.withTtl(Duration.ofMinutes(1), host -> List.of());
        assertThrows(UnknownHostException.class, () -> dns.resolve("nowhere.invalid"));
    }

    @Test
    void failuresAreNotCachedAndRetryCanSucceed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DnsCache dns = DnsCache.withTtl(Duration.ofMinutes(1), host -> {
            if (calls.getAndIncrement() == 0) throw new UnknownHostException(host);
            return List.of(addr(7, 7, 7, 7));
        });
        assertThrows(UnknownHostException.class, () -> dns.resolve("flaky.local"));
        assertEquals(List.of(addr(7, 7, 7, 7)), dns.resolve("flaky.local"));
        assertEquals(2, calls.get());
    }

    @Test
    void invalidateForcesReResolution() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DnsCache dns = DnsCache.withTtl(Duration.ofMinutes(1), host -> {
            calls.incrementAndGet();
            return List.of(addr(5, 5, 5, 5));
        });
        dns.resolve("cached.local");
        dns.invalidate("cached.local");
        dns.resolve("cached.local");
        assertEquals(2, calls.get());
    }

    @Test
    void returnedListIsImmutable() throws Exception {
        DnsCache dns = DnsCache.withTtl(Duration.ofMinutes(1),
                host -> new java.util.ArrayList<>(List.of(addr(2, 2, 2, 2))));
        List<InetAddress> result = dns.resolve("immutable.local");
        assertThrows(UnsupportedOperationException.class, () -> result.add(addr(3, 3, 3, 3)));
    }
}
