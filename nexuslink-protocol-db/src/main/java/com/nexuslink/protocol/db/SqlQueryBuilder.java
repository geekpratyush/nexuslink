package com.nexuslink.protocol.db;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, dialect-agnostic assembler for a {@code SELECT} statement. Feed it a table, an optional
 * column list, a set of {@code WHERE} conditions combined with {@code AND}, an optional
 * {@code ORDER BY} and an optional {@code LIMIT}; it returns a well-formed SQL string.
 *
 * <p>Identifiers (table + column names) are double-quoted and internal quotes doubled; string
 * values are single-quoted and escaped, while numeric literals are emitted bare. This class holds
 * no database state, so it is trivially unit-testable and drives the visual query builder UI.
 */
public final class SqlQueryBuilder {

    /** A comparison against a single column. */
    public enum Operator {
        EQ("="), NE("<>"), LT("<"), LE("<="), GT(">"), GE(">="), LIKE("LIKE"),
        IS_NULL("IS NULL"), IS_NOT_NULL("IS NOT NULL");

        private final String sql;

        Operator(String sql) { this.sql = sql; }

        public String sql() { return sql; }

        /** True when this operator stands alone and takes no value (IS NULL / IS NOT NULL). */
        public boolean takesValue() { return this != IS_NULL && this != IS_NOT_NULL; }

        @Override public String toString() { return sql; }
    }

    /** Sort direction for an {@code ORDER BY} clause. */
    public enum Direction { ASC, DESC }

    /** A single {@code WHERE} predicate: {@code column operator value}. */
    public record Condition(String column, Operator operator, String value) {
        public Condition {
            if (column == null || column.isBlank()) throw new IllegalArgumentException("condition column required");
            if (operator == null) throw new IllegalArgumentException("condition operator required");
        }
    }

    private String table;
    private final List<String> columns = new ArrayList<>();
    private final List<Condition> conditions = new ArrayList<>();
    private String orderBy;
    private Direction orderDir = Direction.ASC;
    private Integer limit;

    public SqlQueryBuilder table(String table) { this.table = table; return this; }

    public SqlQueryBuilder column(String column) {
        if (column != null && !column.isBlank()) columns.add(column.trim());
        return this;
    }

    public SqlQueryBuilder columns(List<String> cols) {
        if (cols != null) for (String c : cols) column(c);
        return this;
    }

    public SqlQueryBuilder where(Condition c) { if (c != null) conditions.add(c); return this; }

    public SqlQueryBuilder where(String column, Operator op, String value) {
        return where(new Condition(column, op, value));
    }

    public SqlQueryBuilder orderBy(String column, Direction dir) {
        this.orderBy = column == null || column.isBlank() ? null : column.trim();
        this.orderDir = dir == null ? Direction.ASC : dir;
        return this;
    }

    public SqlQueryBuilder limit(Integer limit) {
        this.limit = (limit == null || limit <= 0) ? null : limit;
        return this;
    }

    /** Builds the SELECT statement (no trailing semicolon). */
    public String build() {
        if (table == null || table.isBlank()) throw new IllegalStateException("a table is required");

        StringBuilder sb = new StringBuilder("SELECT ");
        if (columns.isEmpty()) {
            sb.append('*');
        } else {
            List<String> quoted = new ArrayList<>(columns.size());
            for (String c : columns) quoted.add(quoteIdent(c));
            sb.append(String.join(", ", quoted));
        }
        sb.append(" FROM ").append(quoteIdent(table.trim()));

        if (!conditions.isEmpty()) {
            List<String> preds = new ArrayList<>(conditions.size());
            for (Condition c : conditions) preds.add(renderCondition(c));
            sb.append(" WHERE ").append(String.join(" AND ", preds));
        }
        if (orderBy != null) {
            sb.append(" ORDER BY ").append(quoteIdent(orderBy)).append(' ').append(orderDir.name());
        }
        if (limit != null) {
            sb.append(" LIMIT ").append(limit);
        }
        return sb.toString();
    }

    private static String renderCondition(Condition c) {
        String col = quoteIdent(c.column().trim());
        Operator op = c.operator();
        if (!op.takesValue()) {
            return col + " " + op.sql();
        }
        return col + " " + op.sql() + " " + literal(c.value());
    }

    /** Double-quotes an identifier, doubling any embedded double-quotes. */
    static String quoteIdent(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /**
     * Renders a WHERE value as a SQL literal: {@code NULL} for null/blank, a bare number when it
     * parses as one, otherwise a single-quoted string with embedded quotes doubled.
     */
    static String literal(String v) {
        if (v == null || v.isBlank()) return "NULL";
        String t = v.trim();
        if (t.matches("-?\\d+(\\.\\d+)?")) return t;
        return "'" + t.replace("'", "''") + "'";
    }
}
