package com.nexuslink.protocol.mongo;

import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three numbers that matter in an explain plan, pulled out of the several hundred lines the
 * server actually returns: which index was used (if any), how many documents were examined, and how
 * many were returned.
 *
 * <p>Raw explain output is technically complete and practically unreadable, so nobody reads it. The
 * ratio of examined to returned is the whole diagnosis: 1:1 means the index did its job, 12,431:3
 * means a collection scan wearing a disguise.
 *
 * <p>Pure: it reads an explain document, so the parsing is testable without a server.
 */
public record ExplainSummary(
        String stage,
        String indexName,
        Document indexKey,
        long documentsExamined,
        long keysExamined,
        long documentsReturned,
        long millis,
        boolean indexUsed
) {

    /** Nothing could be read out of the plan — the raw JSON is still available to the caller. */
    public static ExplainSummary unknown() {
        return new ExplainSummary("", "", null, 0, 0, 0, 0, false);
    }

    /** Reads the winning plan and the execution statistics out of an explain document. */
    public static ExplainSummary of(Document explain) {
        if (explain == null) return unknown();

        Document queryPlanner = explain.get("queryPlanner", Document.class);
        Document winning = queryPlanner == null ? null : queryPlanner.get("winningPlan", Document.class);
        Document leaf = deepestStage(winning);
        String stage = leaf == null ? "" : String.valueOf(leaf.getOrDefault("stage", ""));
        String indexName = leaf == null ? "" : String.valueOf(leaf.getOrDefault("indexName", ""));
        Document indexKey = leaf == null ? null : leaf.get("keyPattern", Document.class);

        Document execution = explain.get("executionStats", Document.class);
        long examined = execution == null ? 0 : asLong(execution.get("totalDocsExamined"));
        long keys = execution == null ? 0 : asLong(execution.get("totalKeysExamined"));
        long returned = execution == null ? 0 : asLong(execution.get("nReturned"));
        long millis = execution == null ? 0 : asLong(execution.get("executionTimeMillis"));

        boolean indexUsed = stage.contains("IXSCAN") || !indexName.isEmpty();
        return new ExplainSummary(stage, indexName, indexKey, examined, keys, returned, millis, indexUsed);
    }

    /** Walks {@code inputStage} down to the leaf — the stage that actually reads the data. */
    private static Document deepestStage(Document plan) {
        Document current = plan;
        while (current != null && current.get("inputStage") instanceof Document next) {
            current = next;
        }
        return current;
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }

    /** {@code true} when the plan reads the whole collection. */
    public boolean isCollectionScan() { return "COLLSCAN".equals(stage); }

    /**
     * Documents examined per document returned. 1.0 is perfect; a large number means the server read
     * a lot to find a little. Returns 0 when nothing was returned.
     */
    public double examinedPerReturned() {
        return documentsReturned == 0 ? 0 : (double) documentsExamined / documentsReturned;
    }

    /** A plain-language verdict for the panel. */
    public String verdict() {
        if (isCollectionScan()) {
            return "Collection scan — every document was read. An index on the filtered fields would fix this.";
        }
        if (!indexUsed) return "No index was used.";
        double ratio = examinedPerReturned();
        if (documentsReturned == 0) return "Index " + indexName + " was used; nothing matched.";
        if (ratio <= 1.5) return "Index " + indexName + " served the query well ("
                + documentsExamined + " examined for " + documentsReturned + " returned).";
        return "Index " + indexName + " was used, but " + documentsExamined
                + " documents were examined to return " + documentsReturned
                + " — the index does not narrow this query much.";
    }

    /** The summary as label/value rows for the details table. */
    public Map<String, String> asRows() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Plan", stage.isEmpty() ? "(unknown)" : stage);
        rows.put("Index", indexUsed ? (indexName.isEmpty() ? "(unnamed)" : indexName) : "(none)");
        if (indexKey != null) rows.put("Index key", indexKey.toJson());
        rows.put("Documents examined", String.valueOf(documentsExamined));
        rows.put("Index keys examined", String.valueOf(keysExamined));
        rows.put("Documents returned", String.valueOf(documentsReturned));
        if (documentsReturned > 0) {
            rows.put("Examined per returned", String.format("%.1f", examinedPerReturned()));
        }
        rows.put("Execution time", millis + " ms");
        rows.put("Verdict", verdict());
        return rows;
    }

    /** The rows rendered as lines, for the result pane. */
    public List<String> asLines() {
        List<String> lines = new ArrayList<>();
        asRows().forEach((k, v) -> lines.add(k + ": " + v));
        return lines;
    }
}
