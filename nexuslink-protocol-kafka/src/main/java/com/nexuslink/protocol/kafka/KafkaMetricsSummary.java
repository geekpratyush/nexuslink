package com.nexuslink.protocol.kafka;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a raw {@code metricName → value} map (as read from an {@code AdminClient}'s metrics) into a
 * curated, human-formatted list of headline rows for a Kafka metrics panel — connection count,
 * request/response rates, byte throughput, latency and I/O wait. Pure and Kafka-type-free so it is
 * fully offline-testable: the caller flattens the client metrics to plain doubles and hands them here.
 * Mirrors {@link ConsumerLagCalculator} and {@link OffsetResetPlanner}.
 */
public final class KafkaMetricsSummary {

    /** One display row: a friendly {@code label} and a formatted {@code value}. */
    public record Metric(String label, String value) {}

    private enum Kind { COUNT, RATE, BYTE_RATE, RATIO, MILLIS }

    /** The curated metrics, in display order: raw metric name → (label, formatting kind). */
    private static final Map<String, Object[]> CURATED = new LinkedHashMap<>();
    static {
        CURATED.put("connection-count", new Object[]{"Open connections", Kind.COUNT});
        CURATED.put("connection-creation-rate", new Object[]{"New connections/s", Kind.RATE});
        CURATED.put("request-rate", new Object[]{"Requests/s", Kind.RATE});
        CURATED.put("response-rate", new Object[]{"Responses/s", Kind.RATE});
        CURATED.put("incoming-byte-rate", new Object[]{"Incoming", Kind.BYTE_RATE});
        CURATED.put("outgoing-byte-rate", new Object[]{"Outgoing", Kind.BYTE_RATE});
        CURATED.put("request-latency-avg", new Object[]{"Avg request latency", Kind.MILLIS});
        CURATED.put("io-wait-ratio", new Object[]{"I/O wait", Kind.RATIO});
    }

    private KafkaMetricsSummary() {}

    /** Builds the headline rows present in {@code raw}, in the curated display order. */
    public static List<Metric> summarize(Map<String, Double> raw) {
        List<Metric> rows = new ArrayList<>();
        if (raw == null) return rows;
        for (Map.Entry<String, Object[]> e : CURATED.entrySet()) {
            Double v = raw.get(e.getKey());
            if (v == null || v.isNaN()) continue;
            rows.add(new Metric((String) e.getValue()[0], format(v, (Kind) e.getValue()[1])));
        }
        return rows;
    }

    private static String format(double v, Kind kind) {
        return switch (kind) {
            case COUNT -> Long.toString(Math.round(v));
            case RATE -> String.format("%.1f/s", v);
            case BYTE_RATE -> humanRate(v);
            case MILLIS -> String.format("%.1f ms", v);
            case RATIO -> String.format("%.1f%%", v * 100);
        };
    }

    /** Formats a byte/second rate, e.g. {@code "1.5 MB/s"}. */
    static String humanRate(double bytesPerSecond) {
        double s = Math.max(0, bytesPerSecond);
        String[] units = {"B/s", "KB/s", "MB/s", "GB/s"};
        int u = 0;
        while (s >= 1024 && u < units.length - 1) { s /= 1024; u++; }
        return u == 0 ? String.format("%.0f %s", s, units[u]) : String.format("%.1f %s", s, units[u]);
    }
}
