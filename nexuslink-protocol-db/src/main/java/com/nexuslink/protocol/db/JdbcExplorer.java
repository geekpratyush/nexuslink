package com.nexuslink.protocol.db;

import com.nexuslink.plugin.ResourceExplorer;
import com.nexuslink.plugin.ResourceNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes a connected {@link JdbcService} as a browsable tree:
 * <pre>database → tables/views → columns</pre>
 * Column nodes carry their SQL type in the details panel.
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
            case DATABASE -> tables();
            case TABLE -> columns(rawTable(parent));
            default -> List.of();
        };
    }

    private List<ResourceNode> tables() throws Exception {
        List<ResourceNode> out = new ArrayList<>();
        for (String t : service.listTables()) {
            boolean view = t.contains("(view)");
            String raw = t.replace("  (view)", "").trim();
            Map<String, String> details = new LinkedHashMap<>();
            details.put("Type", view ? "View" : "Table");
            out.add(new ResourceNode("table:" + raw, t.trim(),
                    view ? ResourceNode.Kind.TABLE : ResourceNode.Kind.TABLE, true, details));
        }
        return out;
    }

    private List<ResourceNode> columns(String table) throws Exception {
        List<ResourceNode> out = new ArrayList<>();
        for (String desc : service.describeTable(table)) {
            // describeTable yields "<name>  <TYPE>"
            String[] parts = desc.trim().split("\\s{2,}", 2);
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

    private String rawTable(ResourceNode table) {
        return table.id().substring("table:".length());
    }
}
