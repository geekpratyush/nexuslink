package com.nexuslink.protocol.mongo;

import org.bson.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a sample of documents says the collection's shape is: for every field path, how often it is
 * present, which BSON types it holds and in what proportion, how often it is null, and how many
 * distinct values were seen.
 *
 * <p>A schemaless store still has a schema — it is just undeclared, and usually inconsistent in ways
 * nobody notices until a query misses documents. Compass's schema tab exists for exactly that, and
 * the two things it makes visible are <em>a field that is sometimes absent</em> and <em>a field
 * stored as two different types</em>. Both are reported here per field, with nested paths flattened
 * ({@code address.city}) so the shape of embedded documents is visible too.
 *
 * <p>Pure: it profiles a list of already-fetched documents, so the sampling policy stays with the
 * caller and every statistic is unit-testable.
 */
public record SchemaProfile(int sampled, List<FieldProfile> fields) {

    /** One field path's statistics across the sample. */
    public record FieldProfile(
            String path,
            int present,
            int sampled,
            int nulls,
            int distinct,
            Map<BsonNode.BsonKind, Integer> types
    ) {
        /** How often the field appears at all, as a percentage of the sample. */
        public double presencePercent() { return sampled == 0 ? 0 : present * 100.0 / sampled; }

        /** How often the field is explicitly null, as a percentage of the documents that have it. */
        public double nullPercent() { return present == 0 ? 0 : nulls * 100.0 / present; }

        /** {@code true} when the field is missing from some documents — the silent query-miss cause. */
        public boolean isOptional() { return present < sampled; }

        /** {@code true} when the field holds more than one BSON type across the sample. */
        public boolean isPolymorphic() { return types.size() > 1; }

        /** The most common type first, as {@code Int32 80% · String 20%}. */
        public String typeSummary() {
            List<Map.Entry<BsonNode.BsonKind, Integer>> entries = new ArrayList<>(types.entrySet());
            entries.sort(Comparator.<Map.Entry<BsonNode.BsonKind, Integer>>comparingInt(Map.Entry::getValue)
                    .reversed());
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<BsonNode.BsonKind, Integer> e : entries) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(e.getKey().label());
                if (present > 0) sb.append(' ').append(Math.round(e.getValue() * 100.0 / present)).append('%');
            }
            return sb.toString();
        }

        /** The dominant type, for the "this field is really an X" column. */
        public BsonNode.BsonKind dominantType() {
            return types.entrySet().stream()
                    .max(Comparator.comparingInt(Map.Entry::getValue))
                    .map(Map.Entry::getKey)
                    .orElse(BsonNode.BsonKind.NULL);
        }
    }

    /**
     * Profiles {@code documents}. Nested documents are flattened into dotted paths; an array is
     * reported as an array (its elements are not profiled individually, which would make the table
     * unreadable for arrays of documents).
     */
    public static SchemaProfile of(List<Document> documents) {
        if (documents == null || documents.isEmpty()) return new SchemaProfile(0, List.of());

        Map<String, Integer> present = new LinkedHashMap<>();
        Map<String, Integer> nulls = new LinkedHashMap<>();
        Map<String, Map<BsonNode.BsonKind, Integer>> types = new LinkedHashMap<>();
        Map<String, Set<String>> values = new LinkedHashMap<>();

        for (Document document : documents) {
            Map<String, Object> flat = new LinkedHashMap<>();
            flatten("", document, flat);
            for (Map.Entry<String, Object> entry : flat.entrySet()) {
                String path = entry.getKey();
                Object value = entry.getValue();
                present.merge(path, 1, Integer::sum);
                BsonNode.BsonKind kind = BsonNode.kindOf(value);
                if (kind == BsonNode.BsonKind.NULL) nulls.merge(path, 1, Integer::sum);
                types.computeIfAbsent(path, k -> new LinkedHashMap<>()).merge(kind, 1, Integer::sum);
                // Distinct is capped: a high-cardinality field only needs to be known as "many".
                Set<String> seen = values.computeIfAbsent(path, k -> new LinkedHashSet<>());
                if (seen.size() < 1000) seen.add(BsonNode.render(value));
            }
        }

        List<FieldProfile> fields = new ArrayList<>(present.size());
        for (String path : present.keySet()) {
            fields.add(new FieldProfile(path, present.get(path), documents.size(),
                    nulls.getOrDefault(path, 0), values.get(path).size(),
                    Map.copyOf(types.get(path))));
        }
        // Fields every document has come first; then the most common of the optional ones.
        fields.sort(Comparator.comparingInt(FieldProfile::present).reversed()
                .thenComparing(FieldProfile::path));
        return new SchemaProfile(documents.size(), fields);
    }

    /** Flattens nested documents into dotted paths; arrays are kept whole. */
    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> document, Map<String, Object> out) {
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            out.put(path, value);
            if (value instanceof Map && !((Map<String, Object>) value).isEmpty()) {
                flatten(path, (Map<String, Object>) value, out);
            }
        }
    }

    /** The fields that are missing from some documents — worth knowing before writing a query. */
    public List<FieldProfile> optionalFields() {
        return fields.stream().filter(FieldProfile::isOptional).toList();
    }

    /** The fields stored as more than one type — the ones that break comparisons and sorts. */
    public List<FieldProfile> polymorphicFields() {
        return fields.stream().filter(FieldProfile::isPolymorphic).toList();
    }
}
