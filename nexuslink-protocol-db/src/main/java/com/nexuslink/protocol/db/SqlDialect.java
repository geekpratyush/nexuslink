package com.nexuslink.protocol.db;

import java.util.Locale;

/**
 * The SQL each engine actually accepts, chosen from the JDBC URL.
 *
 * <p>Statements the workbench generates for you — browsing a table, renaming a column, emptying a
 * table, replacing a view — are not portable. {@code LIMIT 100} is a syntax error on Oracle and SQL
 * Server; SQL Server renames through {@code sp_rename} rather than {@code ALTER TABLE}; SQLite has
 * no {@code TRUNCATE}; MySQL quotes identifiers with backticks and SQL Server with brackets. This
 * enum keeps every one of those differences in one place, so the views can generate SQL without
 * knowing which database they are talking to.
 *
 * <p>Pure and dependency-free: every method is a string transformation that can be unit-tested
 * without a connection.
 */
public enum SqlDialect {

    /** ANSI-ish default: double-quoted identifiers, {@code LIMIT}. Used when the URL is unknown. */
    GENERIC,
    /** PostgreSQL (and CockroachDB / Redshift, which follow it here). */
    POSTGRES,
    /** MySQL and MariaDB: backtick identifiers, {@code RENAME TABLE}. */
    MYSQL,
    /** Oracle: {@code FETCH FIRST … ROWS ONLY}, {@code ADD (col type)}, no {@code COLUMN} keyword. */
    ORACLE,
    /** Microsoft SQL Server: {@code SELECT TOP (n)}, bracket identifiers, {@code sp_rename}. */
    SQLSERVER,
    /** SQLite: no {@code TRUNCATE}, no {@code CREATE OR REPLACE VIEW}. */
    SQLITE,
    /** H2. Close to the ANSI default, listed separately so its quirks have a home. */
    H2,
    /** IBM Db2: {@code FETCH FIRST … ROWS ONLY}. */
    DB2;

    /** The dialect for a JDBC URL; anything unrecognised gets {@link #GENERIC}. */
    public static SqlDialect forUrl(String jdbcUrl) {
        if (jdbcUrl == null) return GENERIC;
        String url = jdbcUrl.toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:postgresql:") || url.startsWith("jdbc:pgsql:")
                || url.startsWith("jdbc:redshift:")) return POSTGRES;
        if (url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:")) return MYSQL;
        if (url.startsWith("jdbc:oracle:")) return ORACLE;
        if (url.startsWith("jdbc:sqlserver:") || url.startsWith("jdbc:jtds:sqlserver:")) return SQLSERVER;
        if (url.startsWith("jdbc:sqlite:")) return SQLITE;
        if (url.startsWith("jdbc:h2:")) return H2;
        if (url.startsWith("jdbc:db2:")) return DB2;
        return GENERIC;
    }

    // ---- identifiers -------------------------------------------------------------------------

    /**
     * Quotes an identifier the way this engine expects — backticks on MySQL, brackets on SQL Server,
     * double quotes elsewhere — doubling any embedded quote character. A name that is already
     * qualified ({@code schema.table}) is quoted part by part, so the dot keeps its meaning.
     */
    public String quote(String name) {
        if (name == null || name.isBlank()) return name == null ? "" : name;
        String trimmed = name.trim();
        if (trimmed.contains(".")) {
            StringBuilder sb = new StringBuilder();
            for (String part : trimmed.split("\\.", -1)) {
                if (sb.length() > 0) sb.append('.');
                sb.append(quoteOne(part));
            }
            return sb.toString();
        }
        return quoteOne(trimmed);
    }

    private String quoteOne(String part) {
        return switch (this) {
            case MYSQL -> "`" + part.replace("`", "``") + "`";
            case SQLSERVER -> "[" + part.replace("]", "]]") + "]";
            default -> "\"" + part.replace("\"", "\"\"") + "\"";
        };
    }

    /** A single-quoted string literal, embedded quotes doubled — the same on every engine here. */
    public String stringLiteral(String value) {
        return value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
    }

    // ---- reading -----------------------------------------------------------------------------

    /**
     * A "show me this table" statement capped at {@code rows} — what a double-click in the schema
     * tree runs. Oracle and Db2 get {@code FETCH FIRST n ROWS ONLY}, SQL Server {@code SELECT TOP
     * (n)}, everyone else {@code LIMIT n}.
     */
    public String selectAll(String table, int rows) {
        String target = quote(table);
        return switch (this) {
            case SQLSERVER -> "SELECT TOP (" + rows + ") * FROM " + target + ";";
            case ORACLE, DB2 -> "SELECT * FROM " + target + " FETCH FIRST " + rows + " ROWS ONLY;";
            default -> "SELECT * FROM " + target + " LIMIT " + rows + ";";
        };
    }

    /**
     * The row-cap clause appended after {@code ORDER BY}, or an empty string on an engine that caps
     * rows with a prefix instead — see {@link #topPrefix(Integer)}.
     */
    public String limitClause(Integer rows) {
        if (rows == null) return "";
        return switch (this) {
            case SQLSERVER -> "";
            case ORACLE, DB2 -> " FETCH FIRST " + rows + " ROWS ONLY";
            default -> " LIMIT " + rows;
        };
    }

    /** The {@code TOP (n)} prefix that goes straight after {@code SELECT}, empty except on SQL Server. */
    public String topPrefix(Integer rows) {
        return rows != null && this == SQLSERVER ? "TOP (" + rows + ") " : "";
    }

    // ---- structure ---------------------------------------------------------------------------

    /** {@code true} when the engine has a real {@code TRUNCATE TABLE}. SQLite does not. */
    public boolean supportsTruncate() { return this != SQLITE; }

    /**
     * Empties a table: {@code TRUNCATE TABLE} where it exists, and an unfiltered {@code DELETE} on
     * SQLite, which has no {@code TRUNCATE}.
     */
    public String truncateTable(String table) {
        if (!supportsTruncate()) return "DELETE FROM " + quote(table) + ";";
        // Db2 requires the IMMEDIATE keyword on TRUNCATE; everyone else rejects it.
        String immediate = this == DB2 ? " IMMEDIATE" : "";
        return "TRUNCATE TABLE " + quote(table) + immediate + ";";
    }

    /** Renames a table — {@code sp_rename} on SQL Server, {@code RENAME TABLE} on MySQL. */
    public String renameTable(String table, String newName) {
        return switch (this) {
            case SQLSERVER -> "EXEC sp_rename " + stringLiteral(table) + ", " + stringLiteral(newName) + ";";
            case MYSQL -> "RENAME TABLE " + quote(table) + " TO " + quote(newName) + ";";
            default -> "ALTER TABLE " + quote(table) + " RENAME TO " + quote(newName) + ";";
        };
    }

    /**
     * Renames a column — {@code sp_rename} on SQL Server, {@code RENAME COLUMN} elsewhere. The
     * {@code RENAME COLUMN} form needs MySQL 8.0 / MariaDB 10.5.2 or newer; older servers want
     * {@code CHANGE}, which needs the column's full type and so cannot be generated from a name alone.
     */
    public String renameColumn(String table, String column, String newName) {
        if (this == SQLSERVER) {
            return "EXEC sp_rename " + stringLiteral(table + "." + column) + ", "
                    + stringLiteral(newName) + ", 'COLUMN';";
        }
        return "ALTER TABLE " + quote(table) + " RENAME COLUMN " + quote(column)
                + " TO " + quote(newName) + ";";
    }

    /**
     * Adds a column. Oracle wraps the definition in parentheses and SQL Server omits the
     * {@code COLUMN} keyword; the rest take {@code ADD COLUMN}.
     */
    public String addColumn(String table, String column, String type) {
        String definition = quote(column) + " " + type.trim();
        return switch (this) {
            case ORACLE -> "ALTER TABLE " + quote(table) + " ADD (" + definition + ");";
            case SQLSERVER -> "ALTER TABLE " + quote(table) + " ADD " + definition + ";";
            default -> "ALTER TABLE " + quote(table) + " ADD COLUMN " + definition + ";";
        };
    }

    /** Drops a column. Oracle and SQL Server take no {@code COLUMN} keyword difference here. */
    public String dropColumn(String table, String column) {
        return "ALTER TABLE " + quote(table) + " DROP COLUMN " + quote(column) + ";";
    }

    /** Drops a table or view. */
    public String dropObject(String kind, String name) {
        return "DROP " + kind.toUpperCase(Locale.ROOT) + " " + quote(name) + ";";
    }

    /**
     * Creates or replaces a view: {@code CREATE OR ALTER VIEW} on SQL Server, a plain
     * {@code CREATE VIEW} on SQLite (which supports neither form of replace), and
     * {@code CREATE OR REPLACE VIEW} everywhere else.
     */
    public String createOrReplaceView(String name, String selectSql) {
        String body = selectSql.strip();
        if (body.endsWith(";")) body = body.substring(0, body.length() - 1);
        String head = switch (this) {
            case SQLSERVER -> "CREATE OR ALTER VIEW ";
            case SQLITE -> "CREATE VIEW ";
            default -> "CREATE OR REPLACE VIEW ";
        };
        return head + quote(name) + " AS\n" + body + ";";
    }

    /** {@code true} when {@code CREATE VIEW} can replace an existing view in one statement. */
    public boolean supportsCreateOrReplaceView() { return this != SQLITE; }

    /** A human-readable name for the status bar and dialogs. */
    public String label() {
        return switch (this) {
            case POSTGRES -> "PostgreSQL";
            case MYSQL -> "MySQL / MariaDB";
            case ORACLE -> "Oracle";
            case SQLSERVER -> "SQL Server";
            case SQLITE -> "SQLite";
            case H2 -> "H2";
            case DB2 -> "Db2";
            case GENERIC -> "ANSI SQL";
        };
    }
}
