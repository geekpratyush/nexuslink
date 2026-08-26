package com.nexuslink.protocol.mongo;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CollectionTransferTest {

    @Test
    void csvColumnsAreTheDottedPathsOfEveryField() {
        List<String> columns = CollectionTransfer.columnsOf(List.of(
                new Document("name", "ada").append("address", new Document("city", "London")),
                new Document("name", "bob").append("age", 41)));
        assertEquals(List.of("name", "address.city", "age"), columns);
    }

    @Test
    void aCsvRowFollowsTheColumnsAndLeavesMissingFieldsEmpty() {
        Document doc = new Document("name", "ada").append("address", new Document("city", "London"));
        assertEquals("ada,London,", CollectionTransfer.toCsvRow(doc, List.of("name", "address.city", "age")));
    }

    @Test
    void csvQuotingProtectsCommasAndQuotes() {
        Document doc = new Document("a", "x,y").append("b", "say \"hi\"");
        assertEquals("\"x,y\",\"say \"\"hi\"\"\"", CollectionTransfer.toCsvRow(doc, List.of("a", "b")));
        assertEquals("\"a,b\",c", CollectionTransfer.toCsvHeader(List.of("a,b", "c")));
    }

    @Test
    void arraysSurviveCsvExportAsJsonRatherThanBeingLost() {
        Document doc = new Document("tags", List.of("a", "b"));
        String row = CollectionTransfer.toCsvRow(doc, List.of("tags"));
        assertTrue(row.contains("\"a\""), row);
        assertTrue(row.contains("\"b\""), row);
    }

    @Test
    void jsonArrayAndJsonLinesAreBothProduced() {
        List<Document> docs = List.of(new Document("a", 1), new Document("a", 2));
        String array = CollectionTransfer.toJsonArray(docs);
        assertTrue(array.startsWith("[\n"));
        assertTrue(array.trim().endsWith("]"));
        assertTrue(array.contains("{\"a\": 1},"));
        assertEquals("{\"a\": 1}", CollectionTransfer.toJsonLine(docs.get(0)));
    }

    @Test
    void bothJsonShapesReadBackWithoutBeingTold() {
        List<Document> fromArray = CollectionTransfer.fromJson("[{\"a\":1},{\"a\":2}]");
        List<Document> fromLines = CollectionTransfer.fromJson("{\"a\":1}\n{\"a\":2}\n");
        assertEquals(2, fromArray.size());
        assertEquals(2, fromLines.size());
        assertEquals(fromArray, fromLines);
        assertTrue(CollectionTransfer.fromJson("").isEmpty());
    }

    @Test
    void aBadJsonLineIsReportedWithItsLineNumber() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CollectionTransfer.fromJson("{\"a\":1}\nnot json\n"));
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
    }

    @Test
    void csvImportRebuildsNestedDocumentsFromDottedHeaders() {
        List<Document> docs = CollectionTransfer.fromCsvRows(
                List.of("name", "address.city", "address.zip"),
                List.of(List.of("ada", "London", "N1")), true);
        assertEquals(1, docs.size());
        assertEquals("ada", docs.get(0).get("name"));
        assertEquals("London", ((Document) docs.get(0).get("address")).get("city"));
        assertEquals("N1", ((Document) docs.get(0).get("address")).get("zip"));
    }

    @Test
    void aBlankMappingEntryDropsThatColumn() {
        List<Document> docs = CollectionTransfer.fromCsvRows(
                List.of("name", "", "age"),
                List.of(List.of("ada", "ignore me", "36")), true);
        assertEquals(List.of("name", "age"), List.copyOf(docs.get(0).keySet()));
    }

    @Test
    void importedValuesAreTypedNotAllStrings() {
        List<Document> docs = CollectionTransfer.fromCsvRows(
                List.of("n", "big", "d", "b", "empty", "when", "text"),
                List.of(Arrays.asList("36", "3000000000", "1.5", "true", "", "2026-08-26T10:15:30Z", "ada")),
                true);
        Document doc = docs.get(0);
        assertEquals(Integer.valueOf(36), doc.get("n"));
        assertEquals(Long.valueOf(3000000000L), doc.get("big"));
        assertEquals(Double.valueOf(1.5), doc.get("d"));
        assertEquals(Boolean.TRUE, doc.get("b"));
        assertNull(doc.get("empty"));
        assertInstanceOf(Date.class, doc.get("when"));
        assertEquals("ada", doc.get("text"));
    }

    @Test
    void typingCanBeSwitchedOffToKeepEverythingAsText() {
        List<Document> docs = CollectionTransfer.fromCsvRows(
                List.of("n"), List.of(List.of("36")), false);
        assertEquals("36", docs.get(0).get("n"));
    }

    @Test
    void aJsonCellBecomesADocumentOrArray() {
        assertInstanceOf(Document.class, CollectionTransfer.typedValue("{\"a\":1}"));
        assertInstanceOf(List.class, CollectionTransfer.typedValue("[1,2]"));
        assertEquals("{not json", CollectionTransfer.typedValue("{not json"));
    }

    @Test
    void aNumberTooLongForALongKeepsItsDigitsAsText() {
        assertEquals("123456789012345678901234567890",
                CollectionTransfer.typedValue("123456789012345678901234567890"));
    }

    @Test
    void everyFormatNamesItsFileExtension() {
        assertEquals("json", CollectionTransfer.Format.JSON_ARRAY.extension());
        assertEquals("jsonl", CollectionTransfer.Format.JSON_LINES.extension());
        assertEquals("csv", CollectionTransfer.Format.CSV.extension());
    }
}
