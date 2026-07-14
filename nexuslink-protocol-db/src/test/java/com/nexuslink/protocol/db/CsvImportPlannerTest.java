package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvImportPlannerTest {

    @Test
    void buildsOneInsertPerRowWithMappedColumns() {
        var inserts = CsvImportPlanner.toInserts("people",
                List.of("id", "name"),
                List.of(List.of("1", "Ada"), List.of("2", "Grace")),
                false);
        assertEquals(2, inserts.size());
        assertEquals("INSERT INTO \"people\" (\"id\", \"name\") VALUES (1, 'Ada')", inserts.get(0));
        assertEquals("INSERT INTO \"people\" (\"id\", \"name\") VALUES (2, 'Grace')", inserts.get(1));
    }

    @Test
    void ignoresUnmappedCsvColumns() {
        // Middle CSV column (blank target) is dropped.
        var inserts = CsvImportPlanner.toInserts("t",
                Arrays.asList("id", "", "name"),
                List.of(List.of("1", "junk", "Ada")),
                false);
        assertEquals(List.of("INSERT INTO \"t\" (\"id\", \"name\") VALUES (1, 'Ada')"), inserts);
    }

    @Test
    void blankCellOmittedForDefaultOrWrittenAsNull() {
        var omitted = CsvImportPlanner.toInserts("t", List.of("id", "name"),
                List.of(List.of("1", "")), false);
        assertEquals(List.of("INSERT INTO \"t\" (\"id\") VALUES (1)"), omitted);

        var asNull = CsvImportPlanner.toInserts("t", List.of("id", "name"),
                List.of(List.of("1", "")), true);
        assertEquals(List.of("INSERT INTO \"t\" (\"id\", \"name\") VALUES (1, NULL)"), asNull);
    }

    @Test
    void skipsRowsThatMapNothingAndValidatesMapping() {
        var inserts = CsvImportPlanner.toInserts("t", List.of("id"),
                List.of(List.of(""), List.of("7")), false);
        assertEquals(List.of("INSERT INTO \"t\" (\"id\") VALUES (7)"), inserts);

        assertThrows(IllegalArgumentException.class,
                () -> CsvImportPlanner.toInserts("t", List.of("", ""), List.of(List.of("a", "b")), false));
        assertThrows(IllegalArgumentException.class,
                () -> CsvImportPlanner.toInserts("", List.of("id"), List.of(List.of("1")), false));
    }
}
