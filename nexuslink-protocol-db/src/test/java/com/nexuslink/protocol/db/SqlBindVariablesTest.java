package com.nexuslink.protocol.db;

import com.nexuslink.protocol.db.SqlBindVariables.Kind;
import com.nexuslink.protocol.db.SqlBindVariables.Variable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlBindVariablesTest {

    @Test
    void findsBindAndSubstitutionVariablesInOrder() {
        List<Variable> vars = SqlBindVariables.scan(
                "SELECT * FROM &table_name WHERE id = :id AND status = :status");
        assertEquals(List.of("table_name", "id", "status"), vars.stream().map(Variable::name).toList());
        assertEquals(Kind.SUBSTITUTION, vars.get(0).kind());
        assertEquals(Kind.BIND, vars.get(1).kind());
    }

    @Test
    void doubleAmpersandIsStickyAndRendersWithBothSigils() {
        List<Variable> vars = SqlBindVariables.scan("SELECT * FROM &&owner.orders");
        assertEquals(1, vars.size());
        assertTrue(vars.get(0).sticky());
        assertEquals("&&owner", vars.get(0).display());
        assertEquals(":id", new Variable("id", Kind.BIND, false).display());
    }

    @Test
    void aNameRepeatedIsPromptedOnce() {
        List<Variable> vars = SqlBindVariables.scan(
                "SELECT * FROM t WHERE a = :id OR b = :id OR c = :id");
        assertEquals(1, vars.size());
        assertEquals("id", vars.get(0).name());
    }

    @Test
    void stringLiteralsAndCommentsAreNotMistakenForVariables() {
        String sql = """
                SELECT 'mailto:someone', "col:name"      -- a note about :notavar
                FROM t /* &alsonotavar */ WHERE x = :real""";
        assertEquals(List.of("real"), SqlBindVariables.scan(sql).stream().map(Variable::name).toList());
    }

    @Test
    void operatorsAreNotMistakenForSubstitutionVariables() {
        assertFalse(SqlBindVariables.hasVariables("SELECT * FROM t WHERE a = 1 AND (flags & 2) > 0"));
        assertFalse(SqlBindVariables.hasVariables("SELECT * FROM t WHERE a && b"));
        assertFalse(SqlBindVariables.hasVariables("SELECT * FROM t WHERE id = ? OR n = $1"));
    }

    @Test
    void bindsBecomePositionalParametersAndNeverEnterTheSql() {
        var prepared = SqlBindVariables.prepare(
                "SELECT * FROM t WHERE name = :name AND owner = :name AND id = :id",
                Map.of("name", "o'brien", "id", "7"));
        assertEquals("SELECT * FROM t WHERE name = ? AND owner = ? AND id = ?", prepared.sql());
        assertEquals(List.of("o'brien", "o'brien", "7"), prepared.values(), "repeated name binds per position");
        assertFalse(prepared.isPlain());
    }

    @Test
    void substitutionsArePastedIntoTheSqlText() {
        var prepared = SqlBindVariables.prepare(
                "SELECT * FROM &tbl ORDER BY &&col DESC", Map.of("tbl", "orders", "col", "created_at"));
        assertEquals("SELECT * FROM orders ORDER BY created_at DESC", prepared.sql());
        assertTrue(prepared.isPlain(), "no JDBC parameters left to bind");
    }

    @Test
    void anUnfilledBindBecomesNullRatherThanFailing() {
        var prepared = SqlBindVariables.prepare("SELECT * FROM t WHERE id = :id", Map.of());
        assertEquals("SELECT * FROM t WHERE id = ?", prepared.sql());
        assertEquals(1, prepared.values().size());
        assertEquals(null, prepared.values().get(0));
    }

    @Test
    void statementWithoutVariablesIsUnchangedAndPlain() {
        var prepared = SqlBindVariables.prepare("SELECT 1", Map.of());
        assertEquals("SELECT 1", prepared.sql());
        assertTrue(prepared.isPlain());
    }

    @Test
    void whitespaceAndFormattingSurviveResolution() {
        String sql = "SELECT *\n  FROM t\n WHERE id = :id";
        assertEquals("SELECT *\n  FROM t\n WHERE id = ?",
                SqlBindVariables.prepare(sql, Map.of("id", "1")).sql());
    }
}
