package com.nexuslink.protocol.mongo;

import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares two collections document by document, matched on {@code _id} — Studio 3T's Data Compare,
 * which is the feature people buy that licence for.
 *
 * <p>The classification mirrors the file commander's {@link com.nexuslink.protocol.mongo.CollectionDiff.Status}
 * idea of a directory diff: present on the left only, on the right only, on both but differing, or
 * identical. For a differing pair the <em>fields</em> that differ are listed, because "these two
 * documents are not equal" is not actionable and "{@code price} and {@code updatedAt} differ" is.
 *
 * <p>The result also generates the script that would make the right side match the left, which is the
 * point of comparing in the first place — and it is generated, never applied, so it can be read
 * before anything is written.
 *
 * <p>Pure: it compares documents already fetched, so the sampling policy and the connection stay with
 * the caller.
 */
public final class CollectionDiff {

    /** How a document on one side relates to the other side. */
    public enum Status {
        /** In the left collection only — would be inserted into the right. */
        LEFT_ONLY,
        /** In the right collection only — would be deleted from the right. */
        RIGHT_ONLY,
        /** In both, with at least one differing field. */
        DIFFERENT,
        /** In both and identical. */
        SAME
    }

    /** One reconciled document pair. */
    public record Entry(String id, Status status, List<String> differingFields,
                        Document left, Document right) {

        /** A one-line summary for the results table. */
        public String summary() {
            return switch (status) {
                case LEFT_ONLY -> "only in left";
                case RIGHT_ONLY -> "only in right";
                case SAME -> "identical";
                case DIFFERENT -> "differs: " + String.join(", ", differingFields);
            };
        }
    }

    private CollectionDiff() {}

    /**
     * Compares two document lists by {@code _id}.
     *
     * <p>Documents without an {@code _id} cannot be matched and are reported as one-sided entries
     * under a synthetic id, rather than being dropped silently.
     */
    public static List<Entry> compare(List<Document> left, List<Document> right) {
        Map<String, Document> leftById = byId(left, "left");
        Map<String, Document> rightById = byId(right, "right");

        Set<String> ids = new LinkedHashSet<>(leftById.keySet());
        ids.addAll(rightById.keySet());

        List<Entry> entries = new ArrayList<>(ids.size());
        for (String id : ids) {
            Document l = leftById.get(id);
            Document r = rightById.get(id);
            if (l == null) {
                entries.add(new Entry(id, Status.RIGHT_ONLY, List.of(), null, r));
            } else if (r == null) {
                entries.add(new Entry(id, Status.LEFT_ONLY, List.of(), l, null));
            } else {
                List<String> differing = differingFields(l, r);
                entries.add(new Entry(id, differing.isEmpty() ? Status.SAME : Status.DIFFERENT,
                        differing, l, r));
            }
        }
        return entries;
    }

    private static Map<String, Document> byId(List<Document> documents, String side) {
        Map<String, Document> out = new LinkedHashMap<>();
        if (documents == null) return out;
        int unmatched = 0;
        for (Document d : documents) {
            Object id = d.get("_id");
            out.put(id == null ? "(" + side + " document without _id #" + (++unmatched) + ")"
                    : String.valueOf(id), d);
        }
        return out;
    }

    /** The field paths that differ between two documents, nested fields included. */
    public static List<String> differingFields(Document left, Document right) {
        Set<String> fields = new LinkedHashSet<>();
        collectDifferences("", left, right, fields);
        return new ArrayList<>(fields);
    }

    @SuppressWarnings("unchecked")
    private static void collectDifferences(String prefix, Map<String, Object> left,
                                           Map<String, Object> right, Set<String> out) {
        Set<String> keys = new LinkedHashSet<>(left.keySet());
        keys.addAll(right.keySet());
        for (String key : keys) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object l = left.get(key);
            Object r = right.get(key);
            if (l instanceof Map && r instanceof Map) {
                collectDifferences(path, (Map<String, Object>) l, (Map<String, Object>) r, out);
            } else if (l == null ? r != null : !l.equals(r)) {
                out.add(path);
            }
        }
    }

    /** How many entries fall into each status — the headline of a comparison. */
    public static Map<Status, Integer> counts(List<Entry> entries) {
        Map<Status, Integer> counts = new LinkedHashMap<>();
        for (Status status : Status.values()) counts.put(status, 0);
        for (Entry e : entries) counts.merge(e.status(), 1, Integer::sum);
        return counts;
    }

    /**
     * The shell script that would make the right collection match the left: inserts for what is
     * missing, replacements for what differs, and deletes for what is extra. Deletes are emitted as
     * comments — removing data is the one direction that should never run because a script was
     * pasted without reading it.
     */
    public static String syncScript(String targetCollection, List<Entry> entries) {
        String target = targetCollection == null || targetCollection.isBlank()
                ? "collection" : targetCollection;
        StringBuilder sb = new StringBuilder("// Makes ").append(target)
                .append(" match the left collection. Review before running.\n");
        for (Entry e : entries) {
            switch (e.status()) {
                case LEFT_ONLY -> sb.append("db.").append(target).append(".insertOne(")
                        .append(e.left().toJson()).append(");\n");
                case DIFFERENT -> sb.append("db.").append(target).append(".replaceOne({\"_id\": ")
                        .append(idLiteral(e.left().get("_id"))).append("}, ")
                        .append(e.left().toJson()).append(");\n");
                case RIGHT_ONLY -> sb.append("// extra on the right — uncomment to remove:\n// db.")
                        .append(target).append(".deleteOne({\"_id\": ")
                        .append(idLiteral(e.right().get("_id"))).append("});\n");
                case SAME -> { }
            }
        }
        return sb.toString();
    }

    /** An {@code _id} rendered as a shell literal — ObjectIds need their constructor. */
    private static String idLiteral(Object id) {
        if (id == null) return "null";
        if (id instanceof org.bson.types.ObjectId oid) return "ObjectId(\"" + oid.toHexString() + "\")";
        if (id instanceof String s) return "\"" + s.replace("\"", "\\\"") + "\"";
        return String.valueOf(id);
    }
}
