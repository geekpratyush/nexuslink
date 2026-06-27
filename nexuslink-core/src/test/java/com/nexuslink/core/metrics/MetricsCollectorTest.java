package com.nexuslink.core.metrics;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsCollectorTest {

    @Test
    void percentileUsesNearestRank() {
        long[] data = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertEquals(5, MetricsCollector.percentile(data, 0.50));
        assertEquals(10, MetricsCollector.percentile(data, 0.95));
        assertEquals(10, MetricsCollector.percentile(data, 0.99));
        assertEquals(1, MetricsCollector.percentile(data, 0.0));
        assertEquals(10, MetricsCollector.percentile(data, 1.0));
    }

    @Test
    void percentileHandlesUnsortedAndEmpty() {
        assertEquals(5, MetricsCollector.percentile(new long[]{7, 3, 9, 1, 5}, 0.50));   // median after sort
        assertEquals(0, MetricsCollector.percentile(new long[]{}, 0.95));
    }

    @Test
    void aggregatesCountErrorsAndLatency() {
        MetricsCollector m = new MetricsCollector();
        for (int i = 1; i <= 10; i++) m.record("rest", i * 10, i != 3, 100);  // one failure (i=3)

        MetricsCollector.Stats s = m.stats("rest");
        assertEquals(10, s.count());
        assertEquals(1, s.errors());
        assertEquals(0.1, s.errorRate(), 1e-9);
        assertEquals(10, s.min());
        assertEquals(100, s.max());
        assertEquals(55.0, s.mean(), 1e-9);     // mean of 10..100 step 10
        assertEquals(1000, s.totalBytes());
        assertEquals(50, s.p50());              // nearest-rank of 10..100
        assertEquals(100, s.p95());
    }

    @Test
    void unknownChannelIsNull() {
        assertNull(new MetricsCollector().stats("nope"));
    }

    @Test
    void snapshotIsOrderedByChannel() {
        MetricsCollector m = new MetricsCollector();
        m.record("zeta", 5, true, 0);
        m.record("alpha", 5, true, 0);
        assertEquals("[alpha, zeta]", m.snapshot().keySet().toString());
    }

    @Test
    void boundedWindowKeepsLatestForPercentilesButLifetimeCountsAll() {
        MetricsCollector m = new MetricsCollector(3);   // tiny window
        for (int i = 1; i <= 10; i++) m.record("c", i, true, 0);
        MetricsCollector.Stats s = m.stats("c");
        assertEquals(10, s.count());            // lifetime count is exact
        // percentiles computed over the last 3 samples {8,9,10}
        assertEquals(10, s.p95());
        assertEquals(9, s.p50());
    }

    @Test
    void throughputCountsSamplesInWindow() {
        MetricsCollector m = new MetricsCollector();
        Instant now = Instant.now();
        m.record("c", 5, true, 0, now.minusSeconds(30));   // outside a 10s window
        m.record("c", 5, true, 0, now.minusSeconds(2));
        m.record("c", 5, true, 0, now.minusSeconds(1));
        // 2 of 3 samples fall in the last 10s → 0.2 req/s
        assertEquals(0.2, m.throughputPerSec("c", Duration.ofSeconds(10)), 0.05);
    }

    @Test
    void clearResets() {
        MetricsCollector m = new MetricsCollector();
        m.record("c", 5, true, 0);
        m.clear();
        assertTrue(m.channels().isEmpty());
    }
}
