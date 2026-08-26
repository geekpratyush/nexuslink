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

    @Test
    void insertStatementsQuoteIdentifiersAndLiterals() {
        String sql = ResultGridExporter.toInsertStatements("people", COLS,
                List.of(List.of("1", "O'Hara", "admin")));
        assertEquals("INSERT INTO \"people\" (\"id\", \"name\", \"role\") "
                + "VALUES (1, 'O''Hara', 'admin');\n", sql);
    }

    @Test
    void insertStatementsWriteNullForNullAndMissingCells() {
        String sql = ResultGridExporter.toInsertStatements("t", COLS, List.of(Arrays.asList("1", null)));
        assertTrue(sql.contains("VALUES (1, NULL, NULL)"), sql);
    }

    @Test
    void insertStatementsFallBackToAPlaceholderTableName() {
        String sql = ResultGridExporter.toInsertStatements("  ", List.of("a"), List.of(List.of("1")));
        assertTrue(sql.startsWith("INSERT INTO \"TABLE\""), sql);
    }

    @Test
    void xmlEscapesTextAndSanitisesColumnNames() {
        String xml = ResultGridExporter.toXml(List.of("First Name", "2nd"),
                List.of(List.of("a<b&c", "x")));
        assertTrue(xml.contains("<first_name>a&lt;b&amp;c</first_name>"), xml);
        assertTrue(xml.contains("<_2nd>x</_2nd>"), xml);
    }

    @Test
    void xmlMarksNullCellsNil() {
        String xml = ResultGridExporter.toXml(List.of("a", "b"), List.of(Arrays.asList("1", null)));
        assertTrue(xml.contains("<b xsi:nil=\"true\"/>"), xml);
    }

    @Test
    void htmlRendersHeaderRowAndEscapesCells() {
        String html = ResultGridExporter.toHtml("People", List.of("name"),
                List.of(List.of("<script>")));
        assertTrue(html.contains("<title>People</title>"), html);
        assertTrue(html.contains("<th>name</th>"), html);
        assertTrue(html.contains("<td>&lt;script&gt;</td>"), html);
    }

    @Test
    void htmlNullCellIsMarkedApartFromAnEmptyString() {
        String html = ResultGridExporter.toHtml(null, List.of("a", "b"),
                List.of(Arrays.asList(null, "")));
        assertTrue(html.contains("<td class=\"null\"></td>"), html);
        assertTrue(html.contains("<td></td>"), html);
    }

    @Test
    void delimitedCsvDefaultsMatchTheCsvExporter() {
        List<List<String>> rows = List.of(List.of("1", "x,y", "admin"));
        assertEquals(ResultGridExporter.toCsv(COLS, rows),
                ResultGridExporter.toDelimited(COLS, rows, ResultGridExporter.Delimited.csv()));
    }

    @Test
    void delimitedTsvEscapesTheSeparatorWhenQuotingIsOff() {
        String out = ResultGridExporter.toDelimited(List.of("a", "b"),
                List.of(Arrays.asList("x\ty", null)), ResultGridExporter.Delimited.tsv());
        String[] lines = out.split("\n");
        assertEquals("a\tb", lines[0]);
        assertEquals("x\\\ty\tNULL", lines[1]);
    }

    @Test
    void delimitedQuoteAllAndNoHeaderAreHonoured() {
        String out = ResultGridExporter.toDelimited(List.of("a", "b"), List.of(List.of("1", "2")),
                new ResultGridExporter.Delimited("|", '\'', true, false, "\n", ""));
        assertEquals("'1'|'2'\n", out);
    }

    @Test
    void delimitedRejectsAnEmptyDelimiter() {
        assertThrows(IllegalArgumentException.class,
                () -> new ResultGridExporter.Delimited("", '"', false, true, "\n", ""));
    }

    @Test
    void markdownRendersAHeaderAlignmentRowAndEscapedCells() {
        String md = ResultGridExporter.toMarkdown(List.of("a", "b"),
                List.of(List.of("x|y", "line1\nline2")));
        String[] lines = md.split("\n");
        assertEquals("| a | b |", lines[0]);
        assertEquals("| --- | --- |", lines[1]);
        assertEquals("| x\\|y | line1<br>line2 |", lines[2]);
    }

    @Test
    void markdownMarksNullCellsApartFromEmptyStrings() {
        String md = ResultGridExporter.toMarkdown(List.of("a", "b"), List.of(Arrays.asList(null, "")));
        assertEquals("| _null_ |  |", md.split("\n")[2]);
    }
}
