package com.nexuslink.protocol.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a portable, human-readable <b>structure export</b> of a database — {@code CREATE TABLE}
 * DDL for the selected tables (columns with types/nullability/defaults, primary keys, foreign keys
 * and indexes), plus a summary of views. The output is plain ANSI-ish SQL suitable for pasting into
 * a ticket, sharing with a teammate, or handing to a coding assistant / GenAI bot as schema context.
 *
 * <p>It reads only JDBC {@link DatabaseMetaData}, so it works across drivers without dialect-specific
 * queries. It never mutates the database.
 */
public final class SchemaExporter {

    private SchemaExporter() { }

    /**
     * Renders DDL for the given tables. A {@code null} or empty selection exports every table and
     * view in the current schema. Identifiers are double-quoted; unknown metadata is skipped rather
     * than failing the whole export.
     */
    public static String toDdl(Connection conn, Collection<String> tables) throws SQLException {
        DatabaseMetaData md = conn.getMetaData();

        // Resolve the object list, split into TABLEs and VIEWs.
        Set<String> want = tables == null ? null
                : new LinkedHashSet<>(tables.stream().map(SchemaExporter::strip).toList());
        List<String> tableNames = new ArrayList<>();
        List<String> viewNames = new ArrayList<>();
        try (ResultSet rs = md.getTables(null, null, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (want != null && !want.contains(name)) continue;
                if ("VIEW".equalsIgnoreCase(rs.getString("TABLE_TYPE"))) viewNames.add(name);
                else tableNames.add(name);
            }
        }

        StringBuilder out = new StringBuilder();
        String product = safeProduct(md);
        out.append("-- Schema export").append(product.isEmpty() ? "" : " from " + product).append('\n');
        out.append("-- ").append(tableNames.size()).append(" table(s)");
        if (!viewNames.isEmpty()) out.append(", ").append(viewNames.size()).append(" view(s)");
        out.append("\n\n");

        if (tableNames.isEmpty() && viewNames.isEmpty()) {
            return out.append("-- (no matching objects)\n").toString();
        }
        for (String t : tableNames) appendTable(out, md, t);
        for (String v : viewNames) appendView(out, md, v);
        return out.toString();
    }

    private static void appendTable(StringBuilder out, DatabaseMetaData md, String table) throws SQLException {
        List<String> lines = new ArrayList<>();

        // Columns
        try (ResultSet rs = md.getColumns(null, null, table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                String type = renderType(rs);
                boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                String def = rs.getString("COLUMN_DEF");
                StringBuilder col = new StringBuilder("    ").append(q(name)).append(' ').append(type);
                if (!nullable) col.append(" NOT NULL");
                if (def != null && !def.isBlank()) col.append(" DEFAULT ").append(def.trim());
                lines.add(col.toString());
            }
        }

        // Primary key (ordered by KEY_SEQ)
        Map<Short, String> pk = new java.util.TreeMap<>();
        try (ResultSet rs = md.getPrimaryKeys(null, null, table)) {
            while (rs.next()) pk.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        }
        if (!pk.isEmpty()) lines.add("    PRIMARY KEY (" + qJoin(pk.values()) + ")");

        // Foreign keys, grouped by constraint name (composite-safe)
        Map<String, Fk> fks = new LinkedHashMap<>();
        try (ResultSet rs = md.getImportedKeys(null, null, table)) {
            while (rs.next()) {
                String fkName = rs.getString("FK_NAME");
                String key = fkName != null ? fkName : rs.getString("PKTABLE_NAME") + ":" + rs.getShort("KEY_SEQ");
                Fk fk = fks.computeIfAbsent(key, k -> new Fk(rs2Parent(rs)));
                try { fk.parent = rs.getString("PKTABLE_NAME"); } catch (SQLException ignore) { }
                fk.cols.add(rs.getString("FKCOLUMN_NAME"));
                fk.refCols.add(rs.getString("PKCOLUMN_NAME"));
            }
        }
        for (Fk fk : fks.values()) {
            lines.add("    FOREIGN KEY (" + qJoin(fk.cols) + ") REFERENCES " + q(fk.parent) + " (" + qJoin(fk.refCols) + ")");
        }

        out.append("CREATE TABLE ").append(q(table)).append(" (\n");
        out.append(String.join(",\n", lines)).append("\n);\n");

        // Non-primary-key indexes
        appendIndexes(out, md, table, new LinkedHashSet<>(pk.values()));
        out.append('\n');
    }

    private static void appendIndexes(StringBuilder out, DatabaseMetaData md, String table, Set<String> pkCols)
            throws SQLException {
        // Gather index → (unique, ordered columns)
        Map<String, boolean[]> unique = new LinkedHashMap<>();
        Map<String, java.util.TreeMap<Short, String>> cols = new LinkedHashMap<>();
        try (ResultSet rs = md.getIndexInfo(null, null, table, false, false)) {
            while (rs.next()) {
                String idx = rs.getString("INDEX_NAME");
                String col = rs.getString("COLUMN_NAME");
                if (idx == null || col == null) continue;   // tableIndexStatistic rows have no column
                boolean uniq = !rs.getBoolean("NON_UNIQUE");
                unique.putIfAbsent(idx, new boolean[]{uniq});
                cols.computeIfAbsent(idx, k -> new java.util.TreeMap<>())
                    .put(rs.getShort("ORDINAL_POSITION"), col);
            }
        }
        for (var e : cols.entrySet()) {
            Collection<String> c = e.getValue().values();
            // Skip the index that merely backs the primary key.
            if (new LinkedHashSet<>(c).equals(pkCols)) continue;
            boolean uniq = unique.get(e.getKey())[0];
            out.append("CREATE ").append(uniq ? "UNIQUE " : "").append("INDEX ").append(q(e.getKey()))
               .append(" ON ").append(q(table)).append(" (").append(qJoin(c)).append(");\n");
        }
    }

    private static void appendView(StringBuilder out, DatabaseMetaData md, String view) throws SQLException {
        out.append("-- VIEW ").append(q(view)).append("  (columns)\n");
        try (ResultSet rs = md.getColumns(null, null, view, "%")) {
            while (rs.next()) {
                out.append("--   ").append(rs.getString("COLUMN_NAME")).append("  ").append(renderType(rs)).append('\n');
            }
        }
        out.append('\n');
    }

    /** Type name with size/scale for the types where it carries meaning. */
    private static String renderType(ResultSet colRow) throws SQLException {
        String type = colRow.getString("TYPE_NAME");
        if (type == null || type.isBlank()) return "UNKNOWN";
        String lower = type.toLowerCase(java.util.Locale.ROOT);
        int size = colRow.getInt("COLUMN_SIZE");
        int digits = safeInt(colRow, "DECIMAL_DIGITS");
        if (type.contains("(")) return type;   // driver already embedded the size
        if (size > 0 && size < 65535 && lower.contains("char")) return type + "(" + size + ")";
        // Guard against drivers (e.g. SQLite) reporting a bogus default precision for numeric types.
        if (size > 0 && size <= 1000 && (lower.equals("decimal") || lower.equals("numeric")))
            return digits > 0 ? type + "(" + size + "," + digits + ")" : type + "(" + size + ")";
        return type;
    }

    // ---- helpers ----

    private static final class Fk {
        String parent;
        final List<String> cols = new ArrayList<>();
        final List<String> refCols = new ArrayList<>();
        Fk(String parent) { this.parent = parent; }
    }

    private static String rs2Parent(ResultSet rs) {
        try { return rs.getString("PKTABLE_NAME"); } catch (SQLException e) { return null; }
    }

    private static int safeInt(ResultSet rs, String col) {
        try { return rs.getInt(col); } catch (SQLException e) { return 0; }
    }

    private static String safeProduct(DatabaseMetaData md) {
        try { return (md.getDatabaseProductName() + " " + md.getDatabaseProductVersion()).trim(); }
        catch (SQLException e) { return ""; }
    }

    private static String strip(String s) {
        return s == null ? "" : s.replace("  (view)", "").trim();
    }

    private static String q(String identifier) {
        return "\"" + (identifier == null ? "" : identifier.replace("\"", "\"\"")) + "\"";
    }

    private static String qJoin(Collection<String> names) {
        List<String> quoted = new ArrayList<>();
        for (String n : names) quoted.add(q(n));
        return String.join(", ", quoted);
    }
}
