package com.nexuslink.protocol.db;

import java.util.Locale;

/**
 * How to read a stored routine's source text back out of a database, chosen from the JDBC URL.
 *
 * <p>JDBC has no portable "show me this procedure's body" call, so every engine needs its own
 * query: PostgreSQL rebuilds the whole definition with {@code pg_get_functiondef}, Oracle stores it
 * line-by-line in {@code ALL_SOURCE}, SQL Server has {@code OBJECT_DEFINITION}, and the SQL-standard
 * {@code INFORMATION_SCHEMA.ROUTINES} covers MySQL/MariaDB/H2 and anything else that implements it.
 *
 * <p>Each constant carries a query with a single {@code ?} bound to the routine name. Pure, so the
 * dialect choice and the SQL are unit-testable without a database.
 */
public enum RoutineSource {

    /** {@code pg_get_functiondef} — returns the full {@code CREATE OR REPLACE FUNCTION …} text. */
    POSTGRES("""
            SELECT pg_get_functiondef(p.oid)
            FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE p.proname = ? AND n.nspname NOT IN ('pg_catalog', 'information_schema')""", false),

    /** {@code ALL_SOURCE} — one row per line of the routine, in order. */
    ORACLE("SELECT text FROM all_source WHERE name = UPPER(?) ORDER BY line", true),

    /** {@code OBJECT_DEFINITION} — the stored batch text for the object. */
    SQLSERVER("SELECT OBJECT_DEFINITION(OBJECT_ID(?))", false),

    /**
     * The SQL-standard catalog. {@code ROUTINE_DEFINITION} holds the body where the engine keeps
     * one; H2 leaves it null for a Java alias, so the external method name stands in.
     */
    INFORMATION_SCHEMA("""
            SELECT COALESCE(ROUTINE_DEFINITION, EXTERNAL_NAME)
            FROM INFORMATION_SCHEMA.ROUTINES
            WHERE UPPER(ROUTINE_NAME) = UPPER(?)""", false);

    private final String query;
    private final boolean lineByLine;

    RoutineSource(String query, boolean lineByLine) {
        this.query = query;
        this.lineByLine = lineByLine;
    }

    /** The query to run, with one {@code ?} to bind the routine name to. */
    public String query() { return query; }

    /** {@code true} when the query returns one row per source line, to be joined with newlines. */
    public boolean isLineByLine() { return lineByLine; }

    /** The mechanism for a JDBC URL. Anything unrecognised falls back to the standard catalog. */
    public static RoutineSource forUrl(String jdbcUrl) {
        if (jdbcUrl == null) return INFORMATION_SCHEMA;
        String url = jdbcUrl.toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:postgresql:") || url.startsWith("jdbc:pgsql:")) return POSTGRES;
        if (url.startsWith("jdbc:oracle:")) return ORACLE;
        if (url.startsWith("jdbc:sqlserver:") || url.startsWith("jdbc:jtds:sqlserver:")) return SQLSERVER;
        return INFORMATION_SCHEMA;
    }
}
