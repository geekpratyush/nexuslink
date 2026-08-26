package com.nexuslink.protocol.kafka;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One record to produce, described fully: topic, key, value, headers, an optional target partition
 * and an optional timestamp — and, crucially, the difference between "empty value" and "no value".
 *
 * <p>That last distinction is not a detail. A record with a null value is a <b>tombstone</b>, and on
 * a compacted topic it is what deletes a key; a producer that can only send strings cannot express
 * it at all, which is why "key + value" is not enough for real Kafka work. Headers matter for the
 * same reason — routing, tracing and schema metadata all live there.
 *
 * <p>Pure: the record and its validation carry no client, so both are unit-testable.
 */
public record ProduceSpec(String topic, String key, String value, boolean tombstone,
                          List<Header> headers, Integer partition, Long timestamp) {

    /** One record header. Kafka allows repeats of the same name, so this is a list, not a map. */
    public record Header(String name, String value) {}

    public ProduceSpec {
        headers = headers == null ? List.of() : List.copyOf(headers);
        if (tombstone) value = null;
    }

    /** A plain record — the common case, no headers, broker picks the partition. */
    public static ProduceSpec of(String topic, String key, String value) {
        return new ProduceSpec(topic, key, value, false, List.of(), null, null);
    }

    /**
     * A tombstone: a null value against {@code key}, which is how a key is deleted from a compacted
     * topic. A tombstone with no key is meaningless — compaction has nothing to match — and
     * {@link #validate()} rejects it.
     */
    public static ProduceSpec tombstone(String topic, String key) {
        return new ProduceSpec(topic, key, null, true, List.of(), null, null);
    }

    /** This record with headers attached. */
    public ProduceSpec withHeaders(List<Header> newHeaders) {
        return new ProduceSpec(topic, key, value, tombstone, newHeaders, partition, timestamp);
    }

    /** This record aimed at one partition rather than letting the partitioner choose. */
    public ProduceSpec withPartition(Integer target) {
        return new ProduceSpec(topic, key, value, tombstone, headers, target, timestamp);
    }

    /** This record carrying an explicit timestamp (epoch millis) rather than "now". */
    public ProduceSpec withTimestamp(Long epochMillis) {
        return new ProduceSpec(topic, key, value, tombstone, headers, partition, epochMillis);
    }

    /**
     * Why this record cannot be sent, or an empty string when it can.
     *
     * @return the reason, for the UI to show before anything is sent
     */
    public String validate() {
        if (topic == null || topic.isBlank()) return "Choose a topic";
        if (tombstone && (key == null || key.isBlank())) {
            return "A tombstone needs a key — it deletes that key from a compacted topic, "
                    + "so without one it deletes nothing";
        }
        if (partition != null && partition < 0) return "The partition cannot be negative";
        if (timestamp != null && timestamp < 0) return "The timestamp cannot be negative";
        for (Header header : headers) {
            if (header.name() == null || header.name().isBlank()) return "A header needs a name";
        }
        return "";
    }

    /** {@code true} when this record can be sent as described. */
    public boolean isValid() { return validate().isEmpty(); }

    /** Headers as a map for display; a repeated name keeps its last value. */
    public Map<String, String> headersAsMap() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Header header : headers) out.put(header.name(), header.value());
        return out;
    }

    /**
     * Parses headers written one per line as {@code name: value} — the compact form the produce panel
     * edits. Blank lines and {@code #} comments are ignored; a line with no colon is skipped rather
     * than becoming a nameless header.
     */
    public static List<Header> parseHeaders(String text) {
        List<Header> headers = new ArrayList<>();
        if (text == null || text.isBlank()) return headers;
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int colon = trimmed.indexOf(':');
            if (colon <= 0) continue;
            headers.add(new Header(trimmed.substring(0, colon).trim(),
                    trimmed.substring(colon + 1).trim()));
        }
        return headers;
    }

    /** Renders headers back to the compact {@code name: value} form. */
    public static String renderHeaders(List<Header> headers) {
        StringBuilder sb = new StringBuilder();
        for (Header header : headers) {
            sb.append(header.name()).append(": ").append(header.value() == null ? "" : header.value())
              .append('\n');
        }
        return sb.toString();
    }

    /** A one-line description for the log, naming a tombstone as such. */
    public String describe() {
        StringBuilder sb = new StringBuilder(topic);
        if (key != null && !key.isBlank()) sb.append("  key=").append(key);
        sb.append(tombstone ? "  (tombstone — deletes this key on a compacted topic)"
                : "  " + (value == null ? 0 : value.length()) + " byte value");
        if (partition != null) sb.append("  → partition ").append(partition);
        if (!headers.isEmpty()) sb.append("  ").append(headers.size()).append(" header(s)");
        return sb.toString();
    }
}
