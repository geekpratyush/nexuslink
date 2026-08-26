package com.nexuslink.ui.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which statements get the stronger confirmation — the warning that the change is permanent plus a
 * tick-box that gates Apply — in the schema tree's preview-then-apply dialog.
 */
class SqlIrreversibleStatementTest {

    @Test
    void droppingAnObjectIsIrreversible() {
        assertTrue(SqlClientView.isIrreversible("DROP TABLE \"people\";"));
        assertTrue(SqlClientView.isIrreversible("DROP VIEW v;"));
        assertTrue(SqlClientView.isIrreversible("DROP PROCEDURE p;"));
    }

    @Test
    void deletingOrTruncatingDataIsIrreversible() {
        assertTrue(SqlClientView.isIrreversible("TRUNCATE TABLE orders;"));
        assertTrue(SqlClientView.isIrreversible("DELETE FROM orders WHERE id = 1;"));
    }

    @Test
    void creatingAndAlteringAreNot() {
        assertFalse(SqlClientView.isIrreversible("CREATE VIEW v AS SELECT 1;"));
        assertFalse(SqlClientView.isIrreversible("ALTER TABLE t ADD COLUMN c INT;"));
        assertFalse(SqlClientView.isIrreversible("ALTER TABLE t RENAME TO u;"));
    }

    @Test
    void leadingWhitespaceAndCaseDoNotHideADrop() {
        assertTrue(SqlClientView.isIrreversible("   \n  drop table t;"));
        assertTrue(SqlClientView.isIrreversible("Drop Table t;"));
    }

    @Test
    void nothingAtAllIsNotIrreversible() {
        assertFalse(SqlClientView.isIrreversible(null));
        assertFalse(SqlClientView.isIrreversible(""));
    }

    @Test
    void aDropWordInsideAStatementDoesNotTriggerIt() {
        assertFalse(SqlClientView.isIrreversible("SELECT * FROM raindrops;"),
                "only the leading keyword decides");
    }
}
