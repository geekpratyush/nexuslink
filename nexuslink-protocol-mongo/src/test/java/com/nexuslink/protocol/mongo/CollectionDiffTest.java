package com.nexuslink.protocol.mongo;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CollectionDiffTest {

    private static Document doc(Object id, String name) {
        return new Document("_id", id).append("name", name);
    }

    @Test
    void documentsAreMatchedByIdAndClassified() {
        List<CollectionDiff.Entry> entries = CollectionDiff.compare(
                List.of(doc(1, "ada"), doc(2, "bob"), doc(3, "cleo")),
                List.of(doc(1, "ada"), doc(2, "robert"), doc(4, "dee")));
        assertEquals(4, entries.size());
        assertEquals(CollectionDiff.Status.SAME, entries.get(0).status());
        assertEquals(CollectionDiff.Status.DIFFERENT, entries.get(1).status());
        assertEquals(CollectionDiff.Status.LEFT_ONLY, entries.get(2).status());
        assertEquals(CollectionDiff.Status.RIGHT_ONLY, entries.get(3).status());
    }

    @Test
    void aDifferingPairNamesTheFieldsThatDiffer() {
        CollectionDiff.Entry entry = CollectionDiff.compare(
                List.of(new Document("_id", 1).append("price", 10).append("qty", 2)),
                List.of(new Document("_id", 1).append("price", 12).append("qty", 2))).get(0);
        assertEquals(List.of("price"), entry.differingFields());
        assertEquals("differs: price", entry.summary());
    }

    @Test
    void nestedFieldsAreComparedByPath() {
        CollectionDiff.Entry entry = CollectionDiff.compare(
                List.of(new Document("_id", 1).append("a", new Document("b", 1).append("c", 2))),
                List.of(new Document("_id", 1).append("a", new Document("b", 9).append("c", 2)))).get(0);
        assertEquals(List.of("a.b"), entry.differingFields());
    }

    @Test
    void aFieldPresentOnOneSideOnlyCountsAsDiffering() {
        CollectionDiff.Entry entry = CollectionDiff.compare(
                List.of(new Document("_id", 1).append("extra", true)),
                List.of(new Document("_id", 1))).get(0);
        assertEquals(List.of("extra"), entry.differingFields());
    }

    @Test
    void aDocumentWithoutAnIdIsReportedRatherThanDropped() {
        List<CollectionDiff.Entry> entries = CollectionDiff.compare(
                List.of(new Document("name", "orphan")), List.of());
        assertEquals(1, entries.size());
        assertEquals(CollectionDiff.Status.LEFT_ONLY, entries.get(0).status());
        assertTrue(entries.get(0).id().contains("without _id"), entries.get(0).id());
    }

    @Test
    void emptyOrNullSidesCompareCleanly() {
        assertTrue(CollectionDiff.compare(List.of(), List.of()).isEmpty());
        assertEquals(1, CollectionDiff.compare(null, List.of(doc(1, "a"))).size());
    }

    @Test
    void theCountsSummariseTheComparison() {
        var counts = CollectionDiff.counts(CollectionDiff.compare(
                List.of(doc(1, "a"), doc(2, "b")), List.of(doc(1, "a"), doc(3, "c"))));
        assertEquals(1, counts.get(CollectionDiff.Status.SAME));
        assertEquals(1, counts.get(CollectionDiff.Status.LEFT_ONLY));
        assertEquals(1, counts.get(CollectionDiff.Status.RIGHT_ONLY));
        assertEquals(0, counts.get(CollectionDiff.Status.DIFFERENT));
    }

    @Test
    void theSyncScriptInsertsReplacesAndOnlyCommentsDeletes() {
        String script = CollectionDiff.syncScript("target", CollectionDiff.compare(
                List.of(doc(1, "ada"), doc(2, "bob")),
                List.of(doc(2, "robert"), doc(3, "cleo"))));
        assertTrue(script.contains("db.target.insertOne("), script);
        assertTrue(script.contains("db.target.replaceOne({\"_id\": 2}"), script);
        assertTrue(script.contains("// db.target.deleteOne({\"_id\": 3})"),
                "a delete must be commented out — it is the one direction that destroys data");
        assertFalse(script.contains("\ndb.target.deleteOne"), script);
    }

    @Test
    void objectIdsAreRenderedAsShellConstructors() {
        ObjectId id = new ObjectId("64b7f2c1a2b3c4d5e6f70819");
        String script = CollectionDiff.syncScript("t", CollectionDiff.compare(
                List.of(doc(id, "ada")), List.of()));
        assertTrue(script.contains("insertOne("), script);
        String replace = CollectionDiff.syncScript("t", CollectionDiff.compare(
                List.of(doc(id, "ada")), List.of(doc(id, "other"))));
        assertTrue(replace.contains("ObjectId(\"64b7f2c1a2b3c4d5e6f70819\")"), replace);
    }

    @Test
    void identicalCollectionsGenerateAScriptWithNoStatements() {
        String script = CollectionDiff.syncScript("t", CollectionDiff.compare(
                List.of(doc(1, "a")), List.of(doc(1, "a"))));
        assertFalse(script.contains("db.t."), script);
    }
}
