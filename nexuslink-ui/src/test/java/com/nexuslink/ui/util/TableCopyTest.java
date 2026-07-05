package com.nexuslink.ui.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TableCopyTest {

    @Test
    void tsvJoinsCellsAndRows() {
        String tsv = TableCopy.toTsv(List.of(List.of("a", "b"), List.of("c", "d")));
        assertEquals("a\tb\nc\td", tsv);
    }

    @Test
    void tsvFlattensEmbeddedTabsAndNewlines() {
        String tsv = TableCopy.toTsv(List.of(List.of("a\tb", "c\nd")));
        assertEquals("a b\tc d", tsv);
    }

    @Test
    void csvQuotesWhenNeeded() {
        String csv = TableCopy.toCsv(List.of(List.of("plain", "has,comma"), List.of("say \"hi\"", "one\ntwo")));
        assertEquals("plain,\"has,comma\"\n\"say \"\"hi\"\"\",\"one\ntwo\"", csv);
    }

    @Test
    void nullCellsRenderEmpty() {
        assertEquals("\tb", TableCopy.toTsv(List.of(Arrays.asList(null, "b"))));
        assertEquals(",b", TableCopy.toCsv(List.of(Arrays.asList(null, "b"))));
    }

    @Test
    void csvCellLeavesPlainValuesUnquoted() {
        assertEquals("hello", TableCopy.csvCell("hello"));
        assertEquals("\"a,b\"", TableCopy.csvCell("a,b"));
    }

    @Test
    void emptyInputYieldsEmptyString() {
        assertEquals("", TableCopy.toTsv(List.of()));
        assertEquals("", TableCopy.toCsv(List.of()));
    }
}
