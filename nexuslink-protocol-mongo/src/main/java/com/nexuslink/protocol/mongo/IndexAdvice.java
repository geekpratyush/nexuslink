package com.nexuslink.protocol.mongo;

import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Index diagnostics without an Atlas account: which indexes are actually being used, which are dead
 * weight, and which index the query in the bar would want.
 *
 * <p>Compass shows this only through cloud-gated Performance Insights; the raw material —
 * {@code $indexStats} usage counters and the query's own field list — is available to any client
 * that bothers to ask. Two rules, both conservative:
 *
 * <ul>
 *   <li><b>Unused</b>: an index whose {@code accesses.ops} is zero since the server last restarted.
 *       Reported with the counter's start time, because "unused" on a server restarted an hour ago
 *       means nothing.</li>
 *   <li><b>Suggested</b>: the equality fields of a filter first, then the sort fields — the
 *       equality-sort-range order that makes an index usable for both. Only suggested when no
 *       existing index already starts with the same fields.</li>
 * </ul>
 *
 * <p>Pure: it works on the documents {@code $indexStats} and {@code listIndexes} return, so the
 * rules can be tested without a server.
 */
public final class IndexAdvice {

    private IndexAdvice() {}

    /** One index's usage since the server's counters started. */
    public record IndexUsage(String name, Document key, long operations, String since) {
        /** {@code true} when nothing has used this index — a candidate for dropping. */
        public boolean isUnused() { return operations == 0; }

        /** {@code true} for the {@code _id} index, which can never be dropped. */
        public boolean isIdIndex() { return "_id_".equals(name); }

        /** The key as {@code {"a": 1, "b": -1}} for display. */
        public String keyLabel() { return key == null ? "{}" : key.toJson(); }
    }

    /** Reads {@code $indexStats} output into usage records. */
    public static List<IndexUsage> usage(List<Document> indexStats) {
        List<IndexUsage> out = new ArrayList<>();
        if (indexStats == null) return out;
        for (Document stat : indexStats) {
            Document accesses = stat.get("accesses", Document.class);
            long ops = 0;
            String since = "";
            if (accesses != null) {
                Object opsValue = accesses.get("ops");
                if (opsValue instanceof Number n) ops = n.longValue();
                Object sinceValue = accesses.get("since");
                if (sinceValue != null) since = BsonNode.render(sinceValue);
            }
            out.add(new IndexUsage(String.valueOf(stat.get("name")),
                    stat.get("key", Document.class), ops, since));
        }
        return out;
    }

    /** The indexes nothing has used, excluding {@code _id_} which cannot be dropped anyway. */
    public static List<IndexUsage> unused(List<Document> indexStats) {
        return usage(indexStats).stream().filter(u -> u.isUnused() && !u.isIdIndex()).toList();
    }

    /**
     * The index a find would want: its equality fields, then its sort fields. Operators other than
     * equality ({@code $gt}, {@code $in}, …) are treated as range fields and go last, which is the
     * order that lets one index serve the filter and the sort together.
     *
     * @return the suggested key, or an empty document when the query needs no index
     */
    public static Document suggestedIndex(Document filter, Document sort) {
        Set<String> equality = new LinkedHashSet<>();
        Set<String> ranges = new LinkedHashSet<>();
        collectFields(filter, equality, ranges);

        Document suggestion = new Document();
        for (String field : equality) suggestion.append(field, 1);
        if (sort != null) {
            for (Map.Entry<String, Object> entry : sort.entrySet()) {
                if (!suggestion.containsKey(entry.getKey())) {
                    suggestion.append(entry.getKey(), entry.getValue() instanceof Number n
                            && n.intValue() < 0 ? -1 : 1);
                }
            }
        }
        for (String field : ranges) if (!suggestion.containsKey(field)) suggestion.append(field, 1);
        return suggestion;
    }

    /** Splits a filter's fields into equality matches and everything else. */
    @SuppressWarnings("unchecked")
    private static void collectFields(Document filter, Set<String> equality, Set<String> ranges) {
        if (filter == null) return;
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();
            if (field.startsWith("$")) {
                // $and / $or / $nor hold arrays of sub-filters.
                if (value instanceof List<?> list) {
                    for (Object sub : list) {
                        if (sub instanceof Document d) collectFields(d, equality, ranges);
                        else if (sub instanceof Map) collectFields(new Document((Map<String, Object>) sub),
                                equality, ranges);
                    }
                }
                continue;
            }
            boolean isOperatorValue = value instanceof Map<?, ?> map && !map.isEmpty()
                    && map.keySet().stream().allMatch(k -> String.valueOf(k).startsWith("$"));
            if (isOperatorValue) ranges.add(field);
            else equality.add(field);
        }
    }

    /**
     * {@code true} when {@code existing} already serves {@code suggested} — an index whose leading
     * fields are the suggestion's fields in the same order (a prefix match, which is how MongoDB
     * chooses an index).
     */
    public static boolean alreadyCovered(Document suggested, List<Document> existingKeys) {
        if (suggested == null || suggested.isEmpty()) return true;
        List<String> wanted = new ArrayList<>(suggested.keySet());
        if (existingKeys == null) return false;
        for (Document key : existingKeys) {
            List<String> have = new ArrayList<>(key.keySet());
            if (have.size() < wanted.size()) continue;
            if (have.subList(0, wanted.size()).equals(wanted)) return true;
        }
        return false;
    }

    /** A one-line recommendation, or an empty string when the existing indexes already cover it. */
    public static String recommendation(Document filter, Document sort, List<Document> existingKeys) {
        Document suggested = suggestedIndex(filter, sort);
        if (suggested.isEmpty()) return "";
        if (alreadyCovered(suggested, existingKeys)) return "";
        return "createIndex(" + suggested.toJson() + ")";
    }
}
