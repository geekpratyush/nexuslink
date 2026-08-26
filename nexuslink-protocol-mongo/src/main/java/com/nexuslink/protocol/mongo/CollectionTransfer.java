package com.nexuslink.protocol.mongo;

import org.bson.Document;
import org.bson.json.JsonWriterSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Turning a collection into a file and back: the pure half of import/export.
 *
 * <p>Three shapes, because that is what people actually have: a <b>JSON array</b> (what most tools
 * emit), <b>JSON lines</b> (what {@code mongoexport} emits, and the only shape that streams without
 * holding the whole collection in memory), and <b>CSV</b> with dotted column names for nested fields.
 *
 * <p>CSV is the awkward one and the rules here are deliberate: on the way out, nested documents are
 * flattened to {@code address.city} columns and arrays are written as JSON so nothing is silently
 * lost; on the way in, a dotted header rebuilds the nesting, and each value is typed by what it looks
 * like — a number stays a number, {@code true}/{@code false} a boolean, an empty cell a null, an
 * ISO-8601 timestamp a date — because a CSV import that stores every field as a string is how a
 * collection ends up unqueryable.
 *
 * <p>Pure: no connection, so both directions are unit-testable.
 */
public final class CollectionTransfer {

    /** How a collection is written out, and read back in. */
    public enum Format {
        /** One JSON array holding every document — the shape most tools import. */
        JSON_ARRAY("json"),
        /** One document per line — what {@code mongoexport} writes, and what streams. */
        JSON_LINES("jsonl"),
        /** Comma-separated, nested fields as dotted columns. */
        CSV("csv");

        private final String extension;
        Format(String extension) { this.extension = extension; }
        public String extension() { return extension; }
    }

    private static final JsonWriterSettings RELAXED =
            JsonWriterSettings.builder().outputMode(org.bson.json.JsonMode.RELAXED).build();

    private CollectionTransfer() {}

    // ---- export ------------------------------------------------------------------------------

    /** Every dotted field path present in {@code documents}, in first-seen order — the CSV header. */
    public static List<String> columnsOf(List<Document> documents) {
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        if (documents != null) {
            for (Document d : documents) collectPaths("", d, columns);
        }
        return new ArrayList<>(columns);
    }

    @SuppressWarnings("unchecked")
    private static void collectPaths(String prefix, Map<String, Object> document, LinkedHashSet<String> out) {
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map && !((Map<String, Object>) value).isEmpty()) {
                collectPaths(path, (Map<String, Object>) value, out);
            } else {
                out.add(path);
            }
        }
    }

    /** One document as a JSON line (relaxed Extended JSON). */
    public static String toJsonLine(Document document) {
        return document.toJson(RELAXED);
    }

    /** The whole list as a JSON array, one document per line inside the brackets. */
    public static String toJsonArray(List<Document> documents) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < documents.size(); i++) {
            sb.append("  ").append(toJsonLine(documents.get(i)));
            if (i < documents.size() - 1) sb.append(',');
            sb.append('\n');
        }
        return sb.append("]\n").toString();
    }

    /** One CSV row for {@code document} against {@code columns} — RFC 4180 quoted, no header. */
    public static String toCsvRow(Document document, List<String> columns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(csvField(valueAtPath(document, columns.get(i))));
        }
        return sb.toString();
    }

    /** The CSV header row for {@code columns}. */
    public static String toCsvHeader(List<String> columns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(csvField(columns.get(i)));
        }
        return sb.toString();
    }

    /** The value at a dotted path, or null when any step is missing. */
    @SuppressWarnings("unchecked")
    public static Object valueAtPath(Map<String, Object> document, String path) {
        Object current = document;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(part);
            if (current == null) return null;
        }
        return current;
    }

    /** A CSV cell: arrays and documents as JSON so nothing is lost, everything else as its text. */
    private static String csvField(Object value) {
        if (value == null) return "";
        String text;
        if (value instanceof List<?> || value instanceof Map<?, ?>) {
            text = new Document("v", value).toJson(RELAXED);
            text = text.substring(text.indexOf(':') + 1, text.lastIndexOf('}')).trim();
        } else {
            text = BsonNode.render(value);
        }
        boolean needsQuote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        return needsQuote ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }

    // ---- import ------------------------------------------------------------------------------

    /**
     * Reads documents from text that is either a JSON array or JSON lines — the two shapes are told
     * apart by the first non-whitespace character, so the caller need not ask the user which it is.
     *
     * @throws IllegalArgumentException if the text is neither
     */
    public static List<Document> fromJson(String text) {
        if (text == null || text.isBlank()) return List.of();
        String trimmed = text.trim();
        List<Document> out = new ArrayList<>();
        if (trimmed.startsWith("[")) {
            for (org.bson.BsonValue value : org.bson.BsonArray.parse(trimmed)) {
                if (!value.isDocument()) {
                    throw new IllegalArgumentException("the array must hold documents, found "
                            + value.getBsonType());
                }
                out.add(Document.parse(value.asDocument().toJson()));
            }
            return out;
        }
        int line = 0;
        for (String row : trimmed.split("\\r?\\n")) {
            line++;
            if (row.isBlank()) continue;
            try {
                out.add(Document.parse(row));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("line " + line + " is not a JSON document: " + e.getMessage());
            }
        }
        return out;
    }

    /**
     * Builds documents from CSV rows. {@code mapping} gives the target field path per column —
     * a blank entry drops that column, which is how the mapping UI excludes one — and dotted paths
     * rebuild nested documents. Values are typed by {@link #typedValue}.
     */
    public static List<Document> fromCsvRows(List<String> mapping, List<List<String>> rows, boolean typed) {
        List<Document> out = new ArrayList<>();
        if (rows == null || mapping == null) return out;
        for (List<String> row : rows) {
            Document document = new Document();
            for (int i = 0; i < mapping.size(); i++) {
                String path = mapping.get(i);
                if (path == null || path.isBlank()) continue;
                String cell = i < row.size() ? row.get(i) : null;
                put(document, path.trim(), typed ? typedValue(cell) : cell);
            }
            if (!document.isEmpty()) out.add(document);
        }
        return out;
    }

    /** Sets a dotted path inside {@code document}, creating the intermediate documents. */
    @SuppressWarnings("unchecked")
    public static void put(Document document, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = document;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) {
                // A Document, not a bare map: the driver encodes it directly and callers can cast.
                next = new Document();
                current.put(parts[i], next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(parts[parts.length - 1], value);
    }

    /**
     * A CSV cell as the BSON value it looks like: blank as null, {@code true}/{@code false} as a
     * boolean, an integer as Int32 or Int64 by size, a decimal as a Double, an ISO-8601 instant as a
     * Date, JSON braces/brackets as a document or array, and anything else as a String.
     */
    public static Object typedValue(String cell) {
        if (cell == null) return null;
        String t = cell.trim();
        if (t.isEmpty()) return null;
        if ("true".equalsIgnoreCase(t)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(t)) return Boolean.FALSE;
        if ("null".equalsIgnoreCase(t)) return null;
        if (t.matches("-?\\d+")) {
            try {
                long value = Long.parseLong(t);
                return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
                        ? (Object) (int) value : (Object) value;
            } catch (NumberFormatException ignored) {
                return t;   // longer than a long: keep the text rather than losing digits
            }
        }
        if (t.matches("-?\\d*\\.\\d+([eE][-+]?\\d+)?")) {
            try {
                return Double.valueOf(t);
            } catch (NumberFormatException ignored) {
                return t;
            }
        }
        if (t.length() >= 20 && t.endsWith("Z")) {
            try {
                return java.util.Date.from(java.time.Instant.parse(t));
            } catch (java.time.format.DateTimeParseException ignored) {
                return t;
            }
        }
        if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
            try {
                return t.startsWith("{") ? Document.parse(t)
                        : Document.parse("{\"v\": " + t + "}").get("v");
            } catch (RuntimeException ignored) {
                return t;
            }
        }
        return t;
    }
}
