package com.nexuslink.protocol.db;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Universal SQL client over JDBC. Holds a single connection for the session and runs
 * statements synchronously (callers run it off the UI thread).
 * <p>
 * SQLite ships in-box; other databases work once their driver jar is on the classpath.
 * The connection URL determines the database (e.g. {@code jdbc:sqlite:/path/db},
 * {@code jdbc:postgresql://host/db}, {@code jdbc:mysql://host/db}).
 */
public final class JdbcService implements AutoCloseable {

    private Connection connection;
    private String url;

    /** Opens a connection. Optional username/password may be blank for embedded DBs. */
    public void connect(String url, String user, String password) throws SQLException {
        connect(url, user, password, Map.of());
    }

    /**
     * Opens a connection with extra driver properties (e.g. the TLS settings produced by
     * {@link JdbcTlsParams#forDriver}). Username/password are folded into the same property set,
     * so the call works for embedded DBs (blank user) and TLS-only configs alike.
     */
    public void connect(String url, String user, String password, Map<String, String> extraProps)
            throws SQLException {
        close();
        this.url = url;
        boolean hasUser = user != null && !user.isBlank();
        boolean hasProps = extraProps != null && !extraProps.isEmpty();
        if (!hasUser && !hasProps) {
            this.connection = DriverManager.getConnection(url);
            return;
        }
        Properties props = new Properties();
        if (hasProps) extraProps.forEach(props::setProperty);
        if (hasUser) {
            props.setProperty("user", user);
            if (password != null) props.setProperty("password", password);
        }
        this.connection = DriverManager.getConnection(url, props);
    }

    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    /** Returns "ProductName vX.Y" for the connected database. */
    public String databaseInfo() throws SQLException {
        DatabaseMetaData md = connection.getMetaData();
        return md.getDatabaseProductName() + " " + md.getDatabaseProductVersion();
    }

    /** Executes a single SQL statement (auto-detects SELECT vs update). */
    public QueryResult execute(String sql) {
        long start = System.nanoTime();
        if (!isConnected()) return QueryResult.error("Not connected", 0);
        try (Statement st = connection.createStatement()) {
            boolean hasResultSet = st.execute(sql);
            if (hasResultSet) {
                try (ResultSet rs = st.getResultSet()) {
                    return readResultSet(rs, ms(start));
                }
            } else {
                return new QueryResult(false, List.of(), List.of(),
                        st.getUpdateCount(), ms(start), false, null);
            }
        } catch (SQLException e) {
            return QueryResult.error(e.getMessage(), ms(start));
        }
    }

    /** Lists table names (and views) in the current schema. */
    public List<String> listTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        DatabaseMetaData md = connection.getMetaData();
        try (ResultSet rs = md.getTables(null, null, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String type = rs.getString("TABLE_TYPE");
                tables.add(rs.getString("TABLE_NAME") + ("VIEW".equals(type) ? "  (view)" : ""));
            }
        }
        return tables;
    }

    /**
     * Builds a Mermaid {@code erDiagram} from the connected database's tables, columns
     * (with PK/FK markers) and foreign-key relationships. Render it with a Mermaid viewer.
     */
    public String erDiagramMermaid() throws SQLException {
        DatabaseMetaData md = connection.getMetaData();
        StringBuilder sb = new StringBuilder("erDiagram\n");

        List<String> tables = new ArrayList<>();
        try (ResultSet rs = md.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) tables.add(rs.getString("TABLE_NAME"));
        }
        if (tables.isEmpty()) return sb.append("  %% no tables found\n").toString();

        // Relationships (deduped): parent ||--o{ child
        java.util.LinkedHashSet<String> rels = new java.util.LinkedHashSet<>();
        for (String t : tables) {
            try (ResultSet rs = md.getImportedKeys(null, null, t)) {
                while (rs.next()) {
                    String parent = rs.getString("PKTABLE_NAME");
                    if (parent != null) rels.add(safe(parent) + " ||--o{ " + safe(t) + " : has");
                }
            }
        }
        for (String rel : rels) sb.append("  ").append(rel).append('\n');

        // Entities
        for (String t : tables) {
            java.util.Set<String> pks = new java.util.HashSet<>();
            try (ResultSet rs = md.getPrimaryKeys(null, null, t)) {
                while (rs.next()) pks.add(rs.getString("COLUMN_NAME"));
            }
            java.util.Set<String> fks = new java.util.HashSet<>();
            try (ResultSet rs = md.getImportedKeys(null, null, t)) {
                while (rs.next()) fks.add(rs.getString("FKCOLUMN_NAME"));
            }
            sb.append("  ").append(safe(t)).append(" {\n");
            try (ResultSet rs = md.getColumns(null, null, t, "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    String type = sanitizeType(rs.getString("TYPE_NAME"));
                    String key = pks.contains(name) ? " PK" : fks.contains(name) ? " FK" : "";
                    sb.append("    ").append(type).append(' ').append(safe(name)).append(key).append('\n');
                }
            }
            sb.append("  }\n");
        }
        return sb.toString();
    }

    private static String safe(String identifier) {
        String s = identifier == null ? "_" : identifier.replaceAll("[^A-Za-z0-9_]", "_");
        return s.isEmpty() ? "_" : (Character.isDigit(s.charAt(0)) ? "_" + s : s);
    }

    private static String sanitizeType(String type) {
        if (type == null || type.isBlank()) return "unknown";
        return type.replaceAll("[^A-Za-z0-9]", "_");
    }

    /** Returns "column TYPE" descriptors for a table. */
    public List<String> describeTable(String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        DatabaseMetaData md = connection.getMetaData();
        try (ResultSet rs = md.getColumns(null, null, table, "%")) {
            while (rs.next()) {
                cols.add(rs.getString("COLUMN_NAME") + "  " + rs.getString("TYPE_NAME"));
            }
        }
        return cols;
    }

    private QueryResult readResultSet(ResultSet rs, long durationMs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int colCount = md.getColumnCount();
        List<String> columns = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) columns.add(md.getColumnLabel(i));

        List<List<String>> rows = new ArrayList<>();
        int limit = 10_000; // guard against runaway result sets in the UI
        while (rs.next() && rows.size() < limit) {
            List<String> row = new ArrayList<>(colCount);
            for (int i = 1; i <= colCount; i++) {
                Object v = rs.getObject(i);
                row.add(v == null ? "NULL" : v.toString());
            }
            rows.add(row);
        }
        return new QueryResult(true, columns, rows, 0, durationMs, false, null);
    }

    private long ms(long startNanos) {
        return Math.round((System.nanoTime() - startNanos) / 1_000_000.0);
    }

    @Override
    public void close() {
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) {}
            connection = null;
        }
    }
}
