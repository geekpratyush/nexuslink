package com.nexuslink.core.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a {@link MetricsCollector} snapshot against configurable alerting thresholds and reports
 * which channels breach them (high error rate or high tail/mean latency). Pure and JavaFX-free so it
 * is unit-testable; the dashboard uses it to flag rows.
 */
public final class MetricsAlerts {

    private MetricsAlerts() {}

    /** What kind of threshold a channel breached. */
    public enum Kind { ERROR_RATE, P95_LATENCY, MEAN_LATENCY }

    /** One breach on one channel. */
    public record Alert(String channel, Kind kind, String message) {}

    /**
     * Alert limits. A threshold {@code <= 0} disables that check.
     *
     * @param maxErrorRate error-rate ceiling in [0,1] (e.g. 0.05 = 5%)
     * @param maxP95Ms     P95 latency ceiling in milliseconds
     * @param maxMeanMs    mean latency ceiling in milliseconds
     * @param minSamples   ignore channels with fewer than this many requests (avoids noise on tiny counts)
     */
    public record Thresholds(double maxErrorRate, long maxP95Ms, long maxMeanMs, long minSamples) {

        /** Reasonable defaults: 5% errors, 2s P95, 1s mean, ignore channels under 5 requests. */
        public static Thresholds defaults() {
            return new Thresholds(0.05, 2000, 1000, 5);
        }
    }

    /** All threshold breaches across the snapshot, in channel order (snapshot is already sorted). */
    public static List<Alert> evaluate(Map<String, MetricsCollector.Stats> snapshot, Thresholds t) {
        List<Alert> alerts = new ArrayList<>();
        if (snapshot == null || t == null) return alerts;
        for (MetricsCollector.Stats s : snapshot.values()) {
            if (s.count() < Math.max(0, t.minSamples())) continue;
            if (t.maxErrorRate() > 0 && s.errorRate() > t.maxErrorRate()) {
                alerts.add(new Alert(s.channel(), Kind.ERROR_RATE,
                        String.format("error rate %.1f%% > %.1f%%", s.errorRate() * 100, t.maxErrorRate() * 100)));
            }
            if (t.maxP95Ms() > 0 && s.p95() > t.maxP95Ms()) {
                alerts.add(new Alert(s.channel(), Kind.P95_LATENCY,
                        "P95 " + s.p95() + "ms > " + t.maxP95Ms() + "ms"));
            }
            if (t.maxMeanMs() > 0 && s.mean() > t.maxMeanMs()) {
                alerts.add(new Alert(s.channel(), Kind.MEAN_LATENCY,
                        String.format("mean %.0fms > %dms", s.mean(), t.maxMeanMs())));
            }
        }
        return alerts;
    }

    /** True if {@code s} breaches any enabled threshold in {@code t}. */
    public static boolean isBreaching(MetricsCollector.Stats s, Thresholds t) {
        if (s == null || t == null || s.count() < Math.max(0, t.minSamples())) return false;
        return (t.maxErrorRate() > 0 && s.errorRate() > t.maxErrorRate())
                || (t.maxP95Ms() > 0 && s.p95() > t.maxP95Ms())
                || (t.maxMeanMs() > 0 && s.mean() > t.maxMeanMs());
    }
}
