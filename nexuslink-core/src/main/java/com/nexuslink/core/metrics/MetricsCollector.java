package com.nexuslink.core.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, in-memory collector of per-channel request metrics — throughput, error rate, and
 * latency percentiles — for the monitoring dashboard. A "channel" is any label the caller groups by
 * (a protocol, a connection name, a host). Lifetime totals (count/errors/bytes/min/max/mean) are
 * exact; percentiles are computed over a bounded window of the most recent samples per channel.
 *
 * <p>{@link #percentile} is a pure nearest-rank helper, unit-tested with known inputs; the collector
 * itself is safe to call from many background threads.
 */
public final class MetricsCollector {

    /** A single recorded request. */
    public record Sample(long latencyMs, boolean success, long bytes, Instant at) {}

    /** An immutable aggregate snapshot for one channel. */
    public record Stats(String channel, long count, long errors, double errorRate,
                        long p50, long p95, long p99, long min, long max, double mean,
                        long totalBytes) {}

    private static final int DEFAULT_WINDOW = 1_000;

    private final int windowSize;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    public MetricsCollector() { this(DEFAULT_WINDOW); }

    public MetricsCollector(int windowSize) { this.windowSize = Math.max(1, windowSize); }

    /** Records a completed request on {@code channel}. */
    public void record(String channel, long latencyMs, boolean success, long bytes) {
        record(channel, latencyMs, success, bytes, Instant.now());
    }

    /** Records a completed request with an explicit timestamp (used by tests / replay). */
    public void record(String channel, long latencyMs, boolean success, long bytes, Instant at) {
        channels.computeIfAbsent(channel, c -> new Channel())
                .add(new Sample(latencyMs, success, bytes, at), windowSize);
    }

    /** Aggregate stats for one channel, or null if it has no samples. */
    public Stats stats(String channel) {
        Channel c = channels.get(channel);
        return c == null ? null : c.stats(channel);
    }

    /** A snapshot of every channel's stats, ordered by channel name. */
    public Map<String, Stats> snapshot() {
        Map<String, Stats> out = new LinkedHashMap<>();
        channels.keySet().stream().sorted().forEach(name -> out.put(name, channels.get(name).stats(name)));
        return out;
    }

    /** Requests per second on {@code channel} over the trailing {@code window} (from now). */
    public double throughputPerSec(String channel, Duration window) {
        Channel c = channels.get(channel);
        return c == null ? 0.0 : c.throughput(window, Instant.now());
    }

    /** Forgets all recorded metrics. */
    public void clear() { channels.clear(); }

    public java.util.Set<String> channels() { return new java.util.TreeSet<>(channels.keySet()); }

    /**
     * Nearest-rank percentile of {@code latencies} (need not be sorted). {@code p} is in [0,1]
     * (e.g. 0.95 for P95). Returns 0 for an empty array.
     */
    public static long percentile(long[] latencies, double p) {
        if (latencies.length == 0) return 0;
        long[] sorted = latencies.clone();
        Arrays.sort(sorted);
        double clamped = Math.max(0.0, Math.min(1.0, p));
        int rank = (int) Math.ceil(clamped * sorted.length);
        int index = Math.max(0, Math.min(sorted.length - 1, rank - 1));
        return sorted[index];
    }

    /** Per-channel mutable state; all access is synchronized on the instance. */
    private static final class Channel {
        private final ArrayDeque<Sample> recent = new ArrayDeque<>();
        private long count;
        private long errors;
        private long totalBytes;
        private long sumLatency;
        private long minLatency = Long.MAX_VALUE;
        private long maxLatency = Long.MIN_VALUE;

        synchronized void add(Sample s, int cap) {
            count++;
            if (!s.success()) errors++;
            totalBytes += s.bytes();
            sumLatency += s.latencyMs();
            minLatency = Math.min(minLatency, s.latencyMs());
            maxLatency = Math.max(maxLatency, s.latencyMs());
            recent.addLast(s);
            while (recent.size() > cap) recent.removeFirst();
        }

        synchronized Stats stats(String name) {
            long[] latencies = recent.stream().mapToLong(Sample::latencyMs).toArray();
            double errorRate = count == 0 ? 0.0 : (double) errors / count;
            double mean = count == 0 ? 0.0 : (double) sumLatency / count;
            return new Stats(name, count, errors, errorRate,
                    percentile(latencies, 0.50), percentile(latencies, 0.95), percentile(latencies, 0.99),
                    count == 0 ? 0 : minLatency, count == 0 ? 0 : maxLatency, mean, totalBytes);
        }

        synchronized double throughput(Duration window, Instant now) {
            Instant cutoff = now.minus(window);
            long inWindow = recent.stream().filter(s -> s.at().isAfter(cutoff)).count();
            double seconds = window.toMillis() / 1000.0;
            return seconds <= 0 ? 0.0 : inWindow / seconds;
        }
    }
}
