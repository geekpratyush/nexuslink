package com.nexuslink.core.metrics;

import java.util.Map;

/**
 * Renders a {@link MetricsCollector} snapshot into a shareable, dependency-free report — CSV (for a
 * spreadsheet) or JSON (for tooling). Pure and JavaFX-free so it is unit-testable; the dashboard's
 * Export button writes the returned text to a file.
 */
public final class MetricsReport {

    private MetricsReport() {}

    private static final String[] HEADERS = {
            "channel", "count", "errors", "errorRate",
            "p50", "p95", "p99", "min", "max", "mean", "totalBytes"
    };

    /** Renders the snapshot as CSV with a header row (RFC 4180 quoting for the channel name). */
    public static String toCsv(Map<String, MetricsCollector.Stats> snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", HEADERS)).append('\n');
        for (MetricsCollector.Stats s : snapshot.values()) {
            sb.append(csv(s.channel())).append(',')
              .append(s.count()).append(',')
              .append(s.errors()).append(',')
              .append(fmt(s.errorRate())).append(',')
              .append(s.p50()).append(',')
              .append(s.p95()).append(',')
              .append(s.p99()).append(',')
              .append(s.min()).append(',')
              .append(s.max()).append(',')
              .append(fmt(s.mean())).append(',')
              .append(s.totalBytes()).append('\n');
        }
        return sb.toString();
    }

    /** Renders the snapshot as a JSON array of objects. */
    public static String toJson(Map<String, MetricsCollector.Stats> snapshot) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (MetricsCollector.Stats s : snapshot.values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\n  {")
              .append("\"channel\":").append(jsonStr(s.channel())).append(',')
              .append("\"count\":").append(s.count()).append(',')
              .append("\"errors\":").append(s.errors()).append(',')
              .append("\"errorRate\":").append(fmt(s.errorRate())).append(',')
              .append("\"p50\":").append(s.p50()).append(',')
              .append("\"p95\":").append(s.p95()).append(',')
              .append("\"p99\":").append(s.p99()).append(',')
              .append("\"min\":").append(s.min()).append(',')
              .append("\"max\":").append(s.max()).append(',')
              .append("\"mean\":").append(fmt(s.mean())).append(',')
              .append("\"totalBytes\":").append(s.totalBytes())
              .append('}');
        }
        sb.append(first ? "]" : "\n]");
        return sb.toString();
    }

    private static String fmt(double d) {
        // Compact, locale-independent: integers print without a fraction, otherwise up to 4 dp.
        if (d == Math.rint(d) && !Double.isInfinite(d)) return Long.toString((long) d);
        return String.format(java.util.Locale.ROOT, "%.4f", d);
    }

    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    private static String jsonStr(String v) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append('"').toString();
    }
}
