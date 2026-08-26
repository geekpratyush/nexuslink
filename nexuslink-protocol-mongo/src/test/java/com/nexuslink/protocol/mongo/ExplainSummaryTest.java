package com.nexuslink.protocol.mongo;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExplainSummaryTest {

    private static Document plan(String stage, String indexName, long examined, long returned) {
        Document leaf = new Document("stage", stage);
        if (indexName != null) {
            leaf.append("indexName", indexName).append("keyPattern", new Document("role", 1));
        }
        return new Document("queryPlanner", new Document("winningPlan",
                        new Document("stage", "FETCH").append("inputStage", leaf)))
                .append("executionStats", new Document("totalDocsExamined", examined)
                        .append("totalKeysExamined", returned)
                        .append("nReturned", returned)
                        .append("executionTimeMillis", 4));
    }

    @Test
    void readsTheWinningPlanDownToTheLeafStage() {
        ExplainSummary summary = ExplainSummary.of(plan("IXSCAN", "role_1", 3, 3));
        assertEquals("IXSCAN", summary.stage());
        assertEquals("role_1", summary.indexName());
        assertTrue(summary.indexUsed());
        assertEquals("{\"role\": 1}", summary.indexKey().toJson());
    }

    @Test
    void readsTheExecutionNumbers() {
        ExplainSummary summary = ExplainSummary.of(plan("IXSCAN", "role_1", 12431, 3));
        assertEquals(12431, summary.documentsExamined());
        assertEquals(3, summary.documentsReturned());
        assertEquals(4, summary.millis());
    }

    @Test
    void aCollectionScanIsRecognisedAndExplained() {
        ExplainSummary summary = ExplainSummary.of(plan("COLLSCAN", null, 12431, 3));
        assertTrue(summary.isCollectionScan());
        assertFalse(summary.indexUsed());
        assertTrue(summary.verdict().contains("Collection scan"), summary.verdict());
        assertTrue(summary.verdict().contains("index"), summary.verdict());
    }

    @Test
    void theExaminedToReturnedRatioIsTheDiagnosis() {
        assertEquals(1.0, ExplainSummary.of(plan("IXSCAN", "role_1", 3, 3)).examinedPerReturned());
        assertEquals(4143.67, Math.round(ExplainSummary.of(plan("IXSCAN", "role_1", 12431, 3))
                .examinedPerReturned() * 100) / 100.0);
        assertEquals(0, ExplainSummary.of(plan("IXSCAN", "role_1", 100, 0)).examinedPerReturned());
    }

    @Test
    void aWellServedQueryGetsAPositiveVerdict() {
        String verdict = ExplainSummary.of(plan("IXSCAN", "role_1", 3, 3)).verdict();
        assertTrue(verdict.contains("served the query well"), verdict);
    }

    @Test
    void aPoorlyNarrowingIndexIsCalledOut() {
        String verdict = ExplainSummary.of(plan("IXSCAN", "role_1", 12431, 3)).verdict();
        assertTrue(verdict.contains("does not narrow"), verdict);
    }

    @Test
    void aQueryThatMatchedNothingSaysSo() {
        assertTrue(ExplainSummary.of(plan("IXSCAN", "role_1", 0, 0)).verdict().contains("nothing matched"));
    }

    @Test
    void anEmptyOrMissingPlanDoesNotThrow() {
        assertEquals(ExplainSummary.unknown().stage(), ExplainSummary.of(null).stage());
        ExplainSummary empty = ExplainSummary.of(new Document());
        assertEquals("", empty.stage());
        assertEquals(0, empty.documentsExamined());
    }

    @Test
    void theRowsCoverEveryNumberAndTheVerdict() {
        var rows = ExplainSummary.of(plan("IXSCAN", "role_1", 12431, 3)).asRows();
        assertEquals("IXSCAN", rows.get("Plan"));
        assertEquals("role_1", rows.get("Index"));
        assertEquals("12431", rows.get("Documents examined"));
        assertEquals("3", rows.get("Documents returned"));
        assertTrue(rows.containsKey("Verdict"));
        assertEquals(rows.size(), ExplainSummary.of(plan("IXSCAN", "role_1", 12431, 3)).asLines().size());
    }
}
