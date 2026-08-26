package com.nexuslink.protocol.db;

import java.util.Locale;

/**
 * How a database hands back the lines a routine printed — Oracle's {@code DBMS_OUTPUT}, PostgreSQL's
 * {@code RAISE NOTICE}, and everyone else's JDBC warnings — decided from the connection URL alone.
 *
 * <p>Oracle buffers output until it is explicitly enabled and then only releases it through
 * {@code DBMS_OUTPUT.GET_LINE}; PostgreSQL and most other drivers surface notices as
 * {@link java.sql.SQLWarning}s on the statement. Keeping the decision here, as a pure function of
 * the URL, keeps {@link JdbcService} free of dialect branching and makes it unit-testable.
 */
public enum ServerOutput {

    /** Enable with {@code DBMS_OUTPUT.ENABLE}, drain with {@code DBMS_OUTPUT.GET_LINE}. */
    DBMS_OUTPUT,
    /** Nothing to enable; lines arrive as JDBC warnings on the executed statement. */
    WARNINGS;

    /** The mechanism for a JDBC URL — Oracle gets {@code DBMS_OUTPUT}, everything else warnings. */
    public static ServerOutput forUrl(String jdbcUrl) {
        if (jdbcUrl == null) return WARNINGS;
        String url = jdbcUrl.toLowerCase(Locale.ROOT);
        return url.startsWith("jdbc:oracle:") ? DBMS_OUTPUT : WARNINGS;
    }

    /** {@code true} when the server buffers output until the client turns it on. */
    public boolean needsEnabling() { return this == DBMS_OUTPUT; }

    /** The PL/SQL block that turns server output on (unlimited buffer). Only for {@link #DBMS_OUTPUT}. */
    public String enableSql() {
        if (this != DBMS_OUTPUT) throw new IllegalStateException(this + " needs no enabling");
        return "begin dbms_output.enable(null); end;";
    }

    /** The PL/SQL block that turns server output off again. Only for {@link #DBMS_OUTPUT}. */
    public String disableSql() {
        if (this != DBMS_OUTPUT) throw new IllegalStateException(this + " needs no enabling");
        return "begin dbms_output.disable; end;";
    }

    /** The callable that fetches one buffered line plus its status. Only for {@link #DBMS_OUTPUT}. */
    public String fetchLineSql() {
        if (this != DBMS_OUTPUT) throw new IllegalStateException(this + " has no fetch call");
        return "{call dbms_output.get_line(?, ?)}";
    }

    /** A human-readable name for the server-output panel's status line. */
    public String label() {
        return this == DBMS_OUTPUT ? "DBMS_OUTPUT" : "server notices";
    }
}
