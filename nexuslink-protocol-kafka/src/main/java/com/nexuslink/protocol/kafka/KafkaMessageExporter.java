package com.nexuslink.protocol.kafka;

import com.nexuslink.protocol.kafka.KafkaService.KafkaMessage;

import java.util.List;

/**
 * Serializes consumed {@link KafkaMessage}s to JSON or CSV for export. Pure and
 * dependency-free (the module has no JSON library), so escaping is done by hand:
 * JSON per RFC 8259, CSV per RFC 4180. Both are total — a null key/value becomes
 * JSON {@code null} / an empty CSV field rather than the text "null".
 */
public final class KafkaMessageExporter {

    private static final String[] CSV_HEADERS = {"partition", "offset", "timestamp", "key", "value"};

    private KafkaMessageExporter() {}

    /** A JSON array of message objects (partition, offset, timestamp, key, value). */
    public static String toJson(List<KafkaMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < messages.size(); i++) {
            KafkaMessage m = messages.get(i);
            if (i > 0) sb.append(',');
            sb.append("\n  {")
              .append("\"partition\": ").append(m.partition()).append(", ")
              .append("\"offset\": ").append(m.offset()).append(", ")
              .append("\"timestamp\": ").append(m.timestamp()).append(", ")
              .append("\"key\": ").append(jsonString(m.key())).append(", ")
              .append("\"value\": ").append(jsonString(m.value()))
              .append('}');
        }
        sb.append(messages.isEmpty() ? "]" : "\n]");
        return sb.toString();
    }

    /** A CSV document with a header row, RFC 4180 quoted. */
    public static String toCsv(List<KafkaMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", CSV_HEADERS)).append("\r\n");
        for (KafkaMessage m : messages) {
            sb.append(m.partition()).append(',')
              .append(m.offset()).append(',')
              .append(m.timestamp()).append(',')
              .append(csvField(m.key())).append(',')
              .append(csvField(m.value()))
              .append("\r\n");
        }
        return sb.toString();
    }

    /** A JSON string literal, or the bare token {@code null}. */
    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** A CSV field, quoted only when it contains a comma, quote, CR or LF. */
    private static String csvField(String s) {
        if (s == null) return "";
        boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needsQuote) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
