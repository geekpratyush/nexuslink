package com.nexuslink.protocol.db;

import com.nexuslink.plugin.ResourceExplorer;
import com.nexuslink.plugin.ResourceNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes a connected {@link JdbcService} as a browsable, categorised tree:
 * <pre>
 * database
 * ├── Tables      → table → columns · indexes · foreign keys
 * ├── Views       → view  → columns
 * ├── Procedures  → procedure
 * └── Functions   → function
 * </pre>
 * Category folders that have no objects are omitted (Tables is always shown). Everything is read
 * lazily from JDBC {@link java.sql.DatabaseMetaData}, so it works across drivers.
 */
public final class JdbcExplorer implements ResourceExplorer {

    private final JdbcService service;

    public JdbcExplorer(JdbcService service) { this.service = service; }

    @Override
    public List<ResourceNode> roots() throws Exception {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("Product", service.databaseInfo());
        ResourceNode db = new ResourceNode("db", service.databaseInfo(),
                ResourceNode.Kind.DATABASE, true, details);
        return List.of(db);
    }

    @Override
    public List<ResourceNode> children(ResourceNode parent) throws Exception {
        return switch (parent.kind()) {
            case DATABASE -> databaseFolders();
            case FOLDER -> folderChildren(parent.id());
            case TABLE -> tableChildren(parent);
            default -> List.of();
        };
    }

    /** Top-level category folders, each labelled with its count; empty ones (bar Tables) are hidden. */
    private List<ResourceNode> databaseFolders() throws Exception {
        int tables = 0, views = 0;
        for (String t : service.listTables()) { if (t.contains("(view)")) views++; else tables++; }
        int procs = service.listProcedures().size();
        int funcs = service.listFunctions().size();

        List<ResourceNode> out = new ArrayList<>();
        out.add(folder("folder:tables", "Tables", tables));                 // always shown
        if (views > 0) out.add(folder("folder:views", "Views", views));
        if (procs > 0) out.add(folder("folder:procedures", "Procedures", procs));
        if (funcs > 0) out.add(folder("folder:functions", "Functions", funcs));
        return out;
    }

    private List<ResourceNode> folderChildren(String folderId) throws Exception {
        return switch (folderId) {
            case "folder:tables" -> tablesOrViews(false);
            case "folder:views" -> tablesOrViews(true);
            case "folder:procedures" -> named("proc:", service.listProcedures(), "Procedure");
            case "folder:functions" -> named("func:", service.listFunctions(), "Function");
            default -> List.of();
        };
    }

    private List<ResourceNode> tablesOrViews(boolean wantViews) throws Exception {
        List<ResourceNode> out = new ArrayList<>();
        for (String t : service.listTables()) {
            boolean view = t.contains("(view)");
            if (view != wantViews) continue;
            String raw = t.replace("  (view)", "").trim();
            Map<String, String> details = new LinkedHashMap<>();
            details.put("Type", view ? "View" : "Table");
            out.add(new ResourceNode((view ? "view:" : "table:") + raw, raw,
                    ResourceNode.Kind.TABLE, true, details));
        }
        return out;
    }

    /** Table children: columns, then indexes, then foreign keys. Views show columns only. */
    private List<ResourceNode> tableChildren(ResourceNode node) throws Exception {
        String id = node.id();
        if (id.startsWith("view:")) return columns(id.substring("view:".length()));

        String table = id.substring("table:".length());
        List<ResourceNode> out = new ArrayList<>(columns(table));
        int i = 0;
        for (String idx : service.listIndexes(table)) {
            Map<String, String> d = new LinkedHashMap<>();
            d.put("Index", idx);
            out.add(new ResourceNode("idx:" + table + "." + (i++), idx, ResourceNode.Kind.INDEX, false, d));
        }
        int f = 0;
        for (String fk : service.listForeignKeys(table)) {
            Map<String, String> d = new LinkedHashMap<>();
            d.put("Foreign key", fk);
            out.add(new ResourceNode("fk:" + table + "." + (f++), fk, ResourceNode.Kind.FIELD, false, d));
        }
        return out;
    }

    private List<ResourceNode> columns(String table) throws Exception {
        List<ResourceNode> out = new ArrayList<>();
        for (String desc : service.describeTable(table)) {
            String[] parts = desc.trim().split("\\s{2,}", 2);   // "<name>  <TYPE>"
            String name = parts[0];
            String type = parts.length > 1 ? parts[1] : "";
            Map<String, String> details = new LinkedHashMap<>();
            details.put("Column", name);
            details.put("Type", type);
            out.add(new ResourceNode("col:" + table + "." + name, desc.trim(),
                    ResourceNode.Kind.COLUMN, false, details));
        }
        return out;
    }

    private List<ResourceNode> named(String idPrefix, List<String> names, String kindLabel) {
        List<ResourceNode> out = new ArrayList<>();
        for (String n : names) {
            Map<String, String> d = new LinkedHashMap<>();
            d.put(kindLabel, n);
            out.add(new ResourceNode(idPrefix + n, n, ResourceNode.Kind.GENERIC, false, d));
        }
        return out;
    }

    private ResourceNode folder(String id, String label, int count) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("Objects", String.valueOf(count));
        return new ResourceNode(id, label + " (" + count + ")", ResourceNode.Kind.FOLDER, true, d);
    }
}
