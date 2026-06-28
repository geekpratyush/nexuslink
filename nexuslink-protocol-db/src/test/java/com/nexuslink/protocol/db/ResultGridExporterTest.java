package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResultGridExporterTest {

    private static final List<String> COLS = List.of("id", "name", "role");

    @Test
    void jsonEmptyRowsIsEmptyArray() {
        assertEquals("[]", ResultGridExporter.toJson(COLS, List.of()));
    }

    @Test
    void jsonObjectPerRowKeyedByHeaders() {
        String json = ResultGridExporter.toJson(COLS, List.of(
                List.of("1", "Alice", "admin"),
                List.of("2", "Bob", "dev")));
        assertTrue(json.contains("\"id\": \"1\""), json);
        assertTrue(json.contains("\"name\": \"Alice\""), json);
        assertTrue(json.contains("\"role\": \"admin\""), json);
        assertTrue(json.contains("\"name\": \"Bob\""), json);
        // Two objects separated by a comma.
        assertEquals(1, json.split("\\},").length - 1);
    }

    @Test
    void jsonEscapesQuotesAndControlChars() {
        String json = ResultGridExporter.toJson(List.of("c"), List.of(
                List.of("he said \"hi\"\nbye\ttab")));
        assertTrue(json.contains("\\\"hi\\\""), json);
        assertTrue(json.contains("\\n"), json);
        assertTrue(json.contains("\\t"), json);
    }

    @Test
    void jsonNullCellIsJsonNull() {
        // A short row (missing trailing cells) and an explicit null both render as null.
        String json = ResultGridExporter.toJson(COLS, List.of(Arrays.asList("1", null)));
        assertTrue(json.contains("\"name\": null"), json);
        assertTrue(json.contains("\"role\": null"), json);
    }

    @Test
    void csvHasHeaderAndRow() {
        String csv = ResultGridExporter.toCsv(COLS, List.of(List.of("1", "Alice", "admin")));
        String[] lines = csv.split("\r\n");
        assertEquals("id,name,role", lines[0]);
        assertEquals("1,Alice,admin", lines[1]);
    }

    @Test
    void csvQuotesCommasQuotesAndNewlines() {
        String csv = ResultGridExporter.toCsv(List.of("a", "b"), List.of(
                List.of("x,y", "line1\nline2 \"q\"")));
        String[] lines = csv.split("\r\n", 2);
        assertTrue(lines[1].contains("\"x,y\""), lines[1]);
        assertTrue(lines[1].contains("\"line1\nline2 \"\"q\"\"\""), lines[1]);
    }

    @Test
    void csvEmptyRowsStillEmitsHeader() {
        String csv = ResultGridExporter.toCsv(COLS, List.of());
        assertEquals("id,name,role\r\n", csv);
    }

    @Test
    void csvNullAndMissingCellsAreEmptyFields() {
        String csv = ResultGridExporter.toCsv(COLS, List.of(Arrays.asList("1", null)));
        String[] lines = csv.split("\r\n");
        // Three columns: id=1, name=null→empty, role=missing→empty.
        assertEquals("1,,", lines[1]);
    }
}
