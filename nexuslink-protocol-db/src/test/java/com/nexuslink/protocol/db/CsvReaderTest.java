package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvReaderTest {

    @Test
    void parsesSimpleRowsAndTrailingNewline() {
        var rows = CsvReader.parse("id,name\n1,Ada\n2,Grace\n");
        assertEquals(3, rows.size());
        assertEquals(List.of("id", "name"), rows.get(0));
        assertEquals(List.of("2", "Grace"), rows.get(2));
    }

    @Test
    void honoursQuotedCommasNewlinesAndDoubledQuotes() {
        var rows = CsvReader.parse("a,b\n\"x,y\",\"line1\nline2\"\n\"she said \"\"hi\"\"\",z");
        assertEquals(3, rows.size());
        assertEquals(List.of("x,y", "line1\nline2"), rows.get(1));
        assertEquals(List.of("she said \"hi\"", "z"), rows.get(2));
    }

    @Test
    void handlesCrlfAndEmptyFields() {
        var rows = CsvReader.parse("a,b,c\r\n1,,3\r\n");
        assertEquals(2, rows.size());
        assertEquals(List.of("1", "", "3"), rows.get(1));
    }

    @Test
    void emptyDocumentYieldsNoRows() {
        assertTrue(CsvReader.parse("").isEmpty());
        assertTrue(CsvReader.parse(null).isEmpty());
    }
}
