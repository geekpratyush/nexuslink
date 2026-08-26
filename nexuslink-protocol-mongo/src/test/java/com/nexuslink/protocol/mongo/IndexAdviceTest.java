package com.nexuslink.protocol.mongo;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndexAdviceTest {

    private static Document stat(String name, Document key, long ops) {
        return new Document("name", name).append("key", key)
                .append("accesses", new Document("ops", ops).append("since", "2026-08-01T00:00:00Z"));
    }

    @Test
    void readsUsageCountersFromIndexStats() {
        List<IndexAdvice.IndexUsage> usage = IndexAdvice.usage(List.of(
                stat("_id_", new Document("_id", 1), 500),
                stat("name_1", new Document("name", 1), 0)));
        assertEquals(2, usage.size());
        assertEquals(500, usage.get(0).operations());
        assertTrue(usage.get(1).isUnused());
        assertEquals("2026-08-01T00:00:00Z", usage.get(0).since());
    }

    @Test
    void theIdIndexIsNeverReportedAsDroppable() {
        List<IndexAdvice.IndexUsage> unused = IndexAdvice.unused(List.of(
                stat("_id_", new Document("_id", 1), 0),
                stat("stale_1", new Document("stale", 1), 0)));
        assertEquals(1, unused.size());
        assertEquals("stale_1", unused.get(0).name());
    }

    @Test
    void malformedOrMissingStatsDoNotThrow() {
        assertTrue(IndexAdvice.usage(null).isEmpty());
        List<IndexAdvice.IndexUsage> usage = IndexAdvice.usage(List.of(new Document("name", "x")));
        assertEquals(0, usage.get(0).operations());
        assertEquals("{}", usage.get(0).keyLabel());
    }

    @Test
    void equalityFieldsComeBeforeSortFields() {
        Document suggestion = IndexAdvice.suggestedIndex(
                new Document("role", "admin"), new Document("name", 1));
        assertEquals(List.of("role", "name"), List.copyOf(suggestion.keySet()));
    }

    @Test
    void rangeFieldsGoLast() {
        Document suggestion = IndexAdvice.suggestedIndex(
                new Document("age", new Document("$gt", 30)).append("role", "admin"),
                new Document("name", 1));
        assertEquals(List.of("role", "name", "age"), List.copyOf(suggestion.keySet()),
                "equality, then sort, then range — the order that serves both");
    }

    @Test
    void sortDirectionIsKept() {
        Document suggestion = IndexAdvice.suggestedIndex(new Document(), new Document("name", -1));
        assertEquals(-1, suggestion.get("name"));
    }

    @Test
    void logicalOperatorsAreLookedInside() {
        Document filter = new Document("$and", List.of(
                new Document("role", "admin"), new Document("active", true)));
        assertEquals(List.of("role", "active"), List.copyOf(IndexAdvice.suggestedIndex(filter, null).keySet()));
    }

    @Test
    void anEmptyQueryNeedsNoIndex() {
        assertTrue(IndexAdvice.suggestedIndex(new Document(), new Document()).isEmpty());
        assertTrue(IndexAdvice.suggestedIndex(null, null).isEmpty());
    }

    @Test
    void anExistingIndexWithTheSameLeadingFieldsCoversTheSuggestion() {
        Document suggested = new Document("role", 1).append("name", 1);
        assertTrue(IndexAdvice.alreadyCovered(suggested,
                List.of(new Document("role", 1).append("name", 1).append("extra", 1))),
                "a longer index whose prefix matches is usable");
        assertFalse(IndexAdvice.alreadyCovered(suggested, List.of(new Document("name", 1).append("role", 1))),
                "the same fields in a different order is a different index");
        assertFalse(IndexAdvice.alreadyCovered(suggested, List.of(new Document("role", 1))));
    }

    @Test
    void theRecommendationIsSilentWhenAnIndexAlreadyServes() {
        Document filter = new Document("role", "admin");
        assertEquals("", IndexAdvice.recommendation(filter, null, List.of(new Document("role", 1))));
        assertTrue(IndexAdvice.recommendation(filter, null, List.of(new Document("_id", 1)))
                .startsWith("createIndex("));
    }
}
