package com.nexuslink.protocol.db;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
                return new QueryResult(false, List.of(), List.of(), List.of(),
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
        return erDiagramMermaid(null);
    }

    /**
     * Same as {@link #erDiagramMermaid()} but limited to the given table names. A {@code null} or
     * empty selection means "all tables". Relationships to tables outside the selection are omitted
     * so the diagram never references an entity that isn't drawn.
     */
    public String erDiagramMermaid(java.util.Collection<String> onlyTables) throws SQLException {
        DatabaseMetaData md = connection.getMetaData();
        StringBuilder sb = new StringBuilder("erDiagram\n");

        List<String> tables = new ArrayList<>();
        try (ResultSet rs = md.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) tables.add(rs.getString("TABLE_NAME"));
        }
        if (onlyTables != null && !onlyTables.isEmpty()) {
            java.util.Set<String> keep = new java.util.HashSet<>(onlyTables);
            tables.removeIf(t -> !keep.contains(t));
        }
        if (tables.isEmpty()) return sb.append("  %% no tables found\n").toString();
        java.util.Set<String> drawn = new java.util.HashSet<>(tables);

        // Relationships (deduped): parent ||--o{ child — only between tables we actually draw.
        java.util.LinkedHashSet<String> rels = new java.util.LinkedHashSet<>();
        for (String t : tables) {
            try (ResultSet rs = md.getImportedKeys(null, null, t)) {
                while (rs.next()) {
                    String parent = rs.getString("PKTABLE_NAME");
                    if (parent != null && drawn.contains(parent)) {
                        rels.add(safe(parent) + " ||--o{ " + safe(t) + " : has");
                    }
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

    /**
     * Exports {@code CREATE TABLE} DDL (columns, keys, indexes) plus a view summary for the given
     * tables — a portable structure dump for sharing or handing to a coding assistant. A {@code null}
     * or empty selection exports every object. See {@link SchemaExporter}.
     */
    public String exportSchema(java.util.Collection<String> tables) throws SQLException {
        return SchemaExporter.toDdl(connection, tables);
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

    /** Primary-key column names for a table, in key order (empty if none). */
    public List<String> primaryKeyColumns(String table) throws SQLException {
        java.util.TreeMap<Short, String> pk = new java.util.TreeMap<>();
        DatabaseMetaData md = connection.getMetaData();
        try (ResultSet rs = md.getPrimaryKeys(null, null, table)) {
            while (rs.next()) pk.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        }
        return new ArrayList<>(pk.values());
    }

    /** Index descriptors for a table: {@code "name (col1, col2)[  UNIQUE]"}. */
    public List<String> listIndexes(String table) throws SQLException {
        DatabaseMetaData md = connection.getMetaData();
        // index name → (unique flag, ordered columns)
        java.util.Map<String, Boolean> unique = new LinkedHashMap<>();
        java.util.Map<String, java.util.TreeMap<Short, String>> cols = new LinkedHashMap<>();
        try (ResultSet rs = md.getIndexInfo(null, null, table, false, false)) {
            while (rs.next()) {
                String idx = rs.getString("INDEX_NAME");
                String col = rs.getString("COLUMN_NAME");
                if (idx == null || col == null) continue;
                unique.putIfAbsent(idx, !rs.getBoolean("NON_UNIQUE"));
                cols.computeIfAbsent(idx, k -> new java.util.TreeMap<>()).put(rs.getShort("ORDINAL_POSITION"), col);
            }
        }
        List<String> out = new ArrayList<>();
        for (var e : cols.entrySet()) {
            out.add(e.getKey() + " (" + String.join(", ", e.getValue().values()) + ")"
                    + (Boolean.TRUE.equals(unique.get(e.getKey())) ? "  UNIQUE" : ""));
        }
        return out;
    }

    /** Foreign-key descriptors for a table: {@code "fkCol → parentTable(pkCol)"}. */
    public List<String> listForeignKeys(String table) throws SQLException {
        DatabaseMetaData md = connection.getMetaData();
        List<String> out = new ArrayList<>();
        try (ResultSet rs = md.getImportedKeys(null, null, table)) {
            while (rs.next()) {
                out.add(rs.getString("FKCOLUMN_NAME") + " → "
                        + rs.getString("PKTABLE_NAME") + "(" + rs.getString("PKCOLUMN_NAME") + ")");
            }
        }
        return out;
    }

    /** Stored-procedure names, best-effort (empty when the driver reports none). */
    public List<String> listProcedures() {
        return metadataNames(md -> md.getProcedures(null, null, "%"), "PROCEDURE_NAME");
    }

    /** User-function names, best-effort (empty when the driver reports none or lacks support). */
    public List<String> listFunctions() {
        return metadataNames(md -> md.getFunctions(null, null, "%"), "FUNCTION_NAME");
    }

    private interface MetaQuery { ResultSet run(DatabaseMetaData md) throws SQLException; }

    private List<String> metadataNames(MetaQuery query, String column) {
        List<String> out = new ArrayList<>();
        try {
            DatabaseMetaData md = connection.getMetaData();
            try (ResultSet rs = query.run(md)) {
                java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                while (rs.next()) {
                    String name = rs.getString(column);
                    if (name != null && !name.isBlank()) seen.add(name.trim());
                }
                out.addAll(seen);
            }
        } catch (SQLException | AbstractMethodError e) {
            // Not all drivers implement getProcedures/getFunctions — treat as "none".
        }
        return out;
    }

    private QueryResult readResultSet(ResultSet rs, long durationMs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int colCount = md.getColumnCount();
        List<String> columns = new ArrayList<>(colCount);
        List<String> columnTypes = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            columns.add(md.getColumnLabel(i));
            columnTypes.add(columnTypeLabel(md, i));
        }

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
        return new QueryResult(true, columns, columnTypes, rows, 0, durationMs, false, null);
    }

    /** A compact, lower-cased column type label for the grid header (e.g. {@code varchar(255)}). */
    private String columnTypeLabel(ResultSetMetaData md, int col) {
        try {
            String name = md.getColumnTypeName(col);
            if (name == null || name.isBlank()) return "";
            name = name.toLowerCase(java.util.Locale.ROOT);
            int precision = md.getPrecision(col);
            // Show a size only for the character/decimal types where it's meaningful.
            if (precision > 0 && precision < 65535
                    && (name.contains("char") || name.contains("varchar") || name.equals("decimal") || name.equals("numeric"))) {
                int scale = md.getScale(col);
                return scale > 0 ? name + "(" + precision + "," + scale + ")" : name + "(" + precision + ")";
            }
            return name;
        } catch (SQLException e) {
            return "";
        }
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
