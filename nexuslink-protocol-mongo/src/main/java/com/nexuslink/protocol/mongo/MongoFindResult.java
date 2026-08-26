package com.nexuslink.protocol.mongo;

import org.bson.Document;

import java.util.List;

/**
 * A find's result in both shapes the UI needs: the decoded {@link Document}s (so the tree view can
 * show true BSON types and edit by {@code _id}) and the same documents rendered as shell JSON (for
 * the JSON view and export). Rendering once here avoids the parse-print-parse round trip that loses
 * type information.
 */
public record MongoFindResult(
        boolean success,
        List<Document> documents,
        List<String> json,
        long durationMs,
        String error
) {
    public static MongoFindResult ok(List<Document> documents, List<String> json, long durationMs) {
        return new MongoFindResult(true, List.copyOf(documents), List.copyOf(json), durationMs, null);
    }

    public static MongoFindResult error(String message, long durationMs) {
        return new MongoFindResult(false, List.of(), List.of(), durationMs, message);
    }

    public int count() { return documents.size(); }

    /** The same shape as {@link MongoQueryResult}, for the code paths that only want the JSON. */
    public MongoQueryResult asQueryResult() {
        return success ? MongoQueryResult.ok(json, durationMs) : MongoQueryResult.error(error, durationMs);
    }
}
