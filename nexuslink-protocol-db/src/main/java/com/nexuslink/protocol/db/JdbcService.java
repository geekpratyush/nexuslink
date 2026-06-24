package com.nexuslink.protocol.db;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

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
        close();
        this.url = url;
        if (user == null || user.isBlank()) {
            this.connection = DriverManager.getConnection(url);
        } else {
            this.connection = DriverManager.getConnection(url, user, password);
        }
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
