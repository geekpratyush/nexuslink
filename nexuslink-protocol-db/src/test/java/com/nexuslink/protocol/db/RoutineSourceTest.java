package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoutineSourceTest {

    @Test
    void eachEngineGetsItsOwnCatalogQuery() {
        assertEquals(RoutineSource.POSTGRES, RoutineSource.forUrl("jdbc:postgresql://h/db"));
        assertEquals(RoutineSource.ORACLE, RoutineSource.forUrl("jdbc:oracle:thin:@//h:1521/orcl"));
        assertEquals(RoutineSource.SQLSERVER, RoutineSource.forUrl("jdbc:sqlserver://h;databaseName=x"));
        assertEquals(RoutineSource.SQLSERVER, RoutineSource.forUrl("jdbc:jtds:sqlserver://h/x"));
    }

    @Test
    void anythingElseFallsBackToTheStandardCatalog() {
        assertEquals(RoutineSource.INFORMATION_SCHEMA, RoutineSource.forUrl("jdbc:mysql://h/db"));
        assertEquals(RoutineSource.INFORMATION_SCHEMA, RoutineSource.forUrl("jdbc:h2:mem:x"));
        assertEquals(RoutineSource.INFORMATION_SCHEMA, RoutineSource.forUrl(null));
    }

    @Test
    void urlMatchingIsCaseInsensitive() {
        assertEquals(RoutineSource.ORACLE, RoutineSource.forUrl("JDBC:ORACLE:THIN:@//h:1521/orcl"));
    }

    @Test
    void everyQueryBindsExactlyOneRoutineName() {
        for (RoutineSource mode : RoutineSource.values()) {
            long placeholders = mode.query().chars().filter(c -> c == '?').count();
            assertEquals(1, placeholders, mode + " should bind the routine name exactly once");
        }
    }

    @Test
    void onlyOracleReturnsTheSourceLineByLine() {
        assertTrue(RoutineSource.ORACLE.isLineByLine());
        assertFalse(RoutineSource.POSTGRES.isLineByLine());
        assertFalse(RoutineSource.INFORMATION_SCHEMA.isLineByLine());
        assertFalse(RoutineSource.SQLSERVER.isLineByLine());
    }
}
