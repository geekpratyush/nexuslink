package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ColumnAggregatesTest {

    @Test
    void numericColumnGetsSumAverageMinAndMax() {
        ColumnAggregates a = ColumnAggregates.of("n",
                List.of(List.of("1"), List.of("2"), List.of("6")), 0);
        assertTrue(a.numeric());
        assertEquals("9", a.sum().toPlainString());
        assertEquals("3", a.average().toPlainString());
        assertEquals("1", a.min().toPlainString());
        assertEquals("6", a.max().toPlainString());
    }

    @Test
    void nullsAreCountedAndExcludedFromTheNumericStatistics() {
        ColumnAggregates a = ColumnAggregates.of("n",
                Arrays.asList(List.of("4"), List.of("NULL"), Arrays.asList((String) null), List.of("6")), 0);
        assertEquals(4, a.count());
        assertEquals(2, a.nulls());
        assertTrue(a.numeric());
        assertEquals("10", a.sum().toPlainString());
        assertEquals("5", a.average().toPlainString());
    }

    @Test
    void aTextColumnHasNoNumericStatistics() {
        ColumnAggregates a = ColumnAggregates.of("name",
                List.of(List.of("ada"), List.of("bob"), List.of("ada")), 0);
        assertFalse(a.numeric());
        assertNull(a.sum());
        assertEquals(2, a.distinct());
    }

    @Test
    void distinctCountsNullAsOneValue() {
        ColumnAggregates a = ColumnAggregates.of("x",
                Arrays.asList(List.of("a"), List.of("NULL"), Arrays.asList((String) null)), 0);
        assertEquals(2, a.distinct());
    }

    @Test
    void anAllNullColumnIsNotTreatedAsNumeric() {
        ColumnAggregates a = ColumnAggregates.of("x", List.of(List.of("NULL"), List.of("NULL")), 0);
        assertFalse(a.numeric());
        assertEquals(2, a.nulls());
    }

    @Test
    void anEmptyGridSummarisesToZeroes() {
        ColumnAggregates a = ColumnAggregates.of("x", List.of(), 0);
        assertEquals(0, a.count());
        assertEquals(0, a.distinct());
        assertFalse(a.numeric());
    }

    @Test
    void missingCellsCountAsNull() {
        ColumnAggregates a = ColumnAggregates.of("b", List.of(List.of("only-one-column")), 1);
        assertEquals(1, a.nulls());
    }

    @Test
    void footerNamesTheColumnAndItsNumbers() {
        String footer = ColumnAggregates.of("id", List.of(List.of("1"), List.of("2")), 0).footer();
        assertEquals("id · 2 rows · 0 null · 2 distinct · sum 3 · avg 1.5 · min 1 · max 2", footer);
    }

    @Test
    void footerOfATextColumnStopsAtDistinct() {
        String footer = ColumnAggregates.of("name", List.of(List.of("ada")), 0).footer();
        assertEquals("name · 1 row · 0 null · 1 distinct", footer);
    }
}
