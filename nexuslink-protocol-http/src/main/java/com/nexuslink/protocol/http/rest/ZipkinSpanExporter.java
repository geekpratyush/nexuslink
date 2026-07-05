package com.nexuslink.protocol.http.rest;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders captured request spans into the <a href="https://zipkin.io/zipkin-api/#/default/post_spans">
 * Zipkin v2 JSON</a> format — the wire format also accepted by Jaeger's Zipkin-compatible collector.
 * Pure and dependency-free (hand-rolled JSON), so a traced REST session can be exported to a
 * {@code .json} file and POSTed to a collector, or opened in a trace UI.
 *
 * <p>Ids are 16-hex (span) / 16- or 32-hex (trace) values as produced by {@link TraceContext}.
 * Timestamps and durations are in <em>microseconds</em> per the Zipkin spec.</p>
 */
public final class ZipkinSpanExporter {

    private ZipkinSpanExporter() {}

    /** Span kind on the wire; {@link #NONE} omits the {@code kind} field. */
    public enum Kind { CLIENT, SERVER, PRODUCER, CONSUMER, NONE }

    /**
     * One span. A NexusLink REST call is normally a single {@link Kind#CLIENT} span whose
     * {@code traceId}/{@code id} come from the request's {@code traceparent}.
     *
     * @param traceId          16- or 32-hex trace id
     * @param id               16-hex span id
     * @param parentId         16-hex parent span id, or null/blank for a root span
     * @param name             span name (e.g. {@code "GET /users"})
     * @param kind             span kind (use {@link Kind#NONE} to omit)
     * @param timestampMicros  start time, epoch microseconds
     * @param durationMicros   duration in microseconds ({@code >= 1}; clamped)
     * @param localServiceName local endpoint service name (omitted when null/blank)
     * @param tags             string key/value tags (e.g. http.method, http.status_code)
     */
    public record Span(String traceId, String id, String parentId, String name, Kind kind,
                       long timestampMicros, long durationMicros, String localServiceName,
                       Map<String, String> tags) {}

    /** Renders one span as a JSON object. */
    public static String toJson(Span span) {
        StringBuilder sb = new StringBuilder();
        writeSpan(sb, span);
        return sb.toString();
    }

    /** Renders a list of spans as a JSON array (the shape a Zipkin collector accepts). */
    public static String toJsonArray(List<Span> spans) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < spans.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("\n  ");
            writeSpan(sb, spans.get(i));
        }
        sb.append(spans.isEmpty() ? "]" : "\n]");
        return sb.toString();
    }

    private static void writeSpan(StringBuilder sb, Span s) {
        sb.append('{');
        field(sb, "traceId", s.traceId(), true);
        field(sb, "id", s.id(), false);
        if (s.parentId() != null && !s.parentId().isBlank()) field(sb, "parentId", s.parentId(), false);
        if (s.name() != null) field(sb, "name", s.name(), false);
        if (s.kind() != null && s.kind() != Kind.NONE) field(sb, "kind", s.kind().name(), false);
        sb.append(",\"timestamp\":").append(s.timestampMicros());
        sb.append(",\"duration\":").append(Math.max(1, s.durationMicros()));
        if (s.localServiceName() != null && !s.localServiceName().isBlank()) {
            sb.append(",\"localEndpoint\":{\"serviceName\":").append(quote(s.localServiceName())).append('}');
        }
        if (s.tags() != null && !s.tags().isEmpty()) {
            sb.append(",\"tags\":{");
            boolean first = true;
            // Sorted for stable, testable output.
            for (Map.Entry<String, String> e : new TreeMap<>(s.tags()).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(quote(e.getKey())).append(':').append(quote(String.valueOf(e.getValue())));
            }
            sb.append('}');
        }
        sb.append('}');
    }

    private static void field(StringBuilder sb, String key, String value, boolean first) {
        if (!first) sb.append(',');
        sb.append(quote(key)).append(':').append(quote(value));
    }

    private static String quote(String v) {
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
