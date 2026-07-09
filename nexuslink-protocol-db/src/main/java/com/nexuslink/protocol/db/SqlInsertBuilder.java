package com.nexuslink.protocol.db;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, dialect-agnostic assembler for a single-row {@code INSERT} statement. Feed it a table and
 * one or more {@code column → value} pairs; it returns a well-formed
 * {@code INSERT INTO "t" ("c1", "c2") VALUES (v1, v2)} string.
 *
 * <p>Columns the user never sets are simply omitted, so the database supplies its own default
 * (auto-increment / serial keys, {@code DEFAULT} expressions, timestamps). To force a literal
 * {@code NULL} into a column, add it explicitly with {@link #valueNull(String)}.
 *
 * <p>Identifiers are double-quoted (embedded quotes doubled) and values rendered with the same
 * {@link SqlQueryBuilder#literal literal} rules as the visual query builder — numbers bare, strings
 * single-quoted and escaped. The class holds no database state, so it is trivially unit-testable and
 * drives the "Insert row" form in the SQL workbench.
 */
public final class SqlInsertBuilder {

    /** A column paired with the pre-rendered SQL literal to store in it. */
    private record Assignment(String column, String literal) {}

    private String table;
    private final List<Assignment> assignments = new ArrayList<>();

    public SqlInsertBuilder table(String table) { this.table = table; return this; }

    /** Sets {@code column} to {@code value}, rendered as a number or an escaped string literal. */
    public SqlInsertBuilder value(String column, String value) {
        return add(column, SqlQueryBuilder.literal(value));
    }

    /** Sets {@code column} to a raw pre-rendered SQL literal (already quoted/escaped by the caller). */
    public SqlInsertBuilder valueLiteral(String column, String literal) {
        return add(column, literal == null || literal.isBlank() ? "NULL" : literal.trim());
    }

    /** Sets {@code column} explicitly to {@code NULL}. */
    public SqlInsertBuilder valueNull(String column) { return add(column, "NULL"); }

    private SqlInsertBuilder add(String column, String literal) {
        if (column == null || column.isBlank()) throw new IllegalArgumentException("insert column required");
        assignments.add(new Assignment(column.trim(), literal));
        return this;
    }

    /** Builds the INSERT statement (no trailing semicolon). */
    public String build() {
        if (table == null || table.isBlank()) throw new IllegalStateException("a table is required");
        if (assignments.isEmpty()) throw new IllegalStateException("at least one column value is required");

        List<String> cols = new ArrayList<>(assignments.size());
        List<String> vals = new ArrayList<>(assignments.size());
        for (Assignment a : assignments) {
            cols.add(SqlQueryBuilder.quoteIdent(a.column()));
            vals.add(a.literal());
        }
        return "INSERT INTO " + SqlQueryBuilder.quoteIdent(table.trim())
                + " (" + String.join(", ", cols) + ") VALUES (" + String.join(", ", vals) + ")";
    }
}
