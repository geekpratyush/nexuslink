package com.nexuslink.protocol.mongo;

import com.nexuslink.plugin.ResourceExplorer;
import com.nexuslink.plugin.ResourceNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes a connected {@link MongoService} as a browsable tree:
 * <pre>databases → collections → (indexes + stats)</pre>
 * Index nodes carry the index definition as details; collection nodes carry collStats.
 */
public final class MongoExplorer implements ResourceExplorer {

    private final MongoService service;

    public MongoExplorer(MongoService service) { this.service = service; }

    @Override
    public List<ResourceNode> roots() {
        List<ResourceNode> dbs = new ArrayList<>();
        for (String name : service.listDatabaseNames()) {
            dbs.add(ResourceNode.branch("db:" + name, name, ResourceNode.Kind.DATABASE));
        }
        return dbs;
    }

    @Override
    public List<ResourceNode> children(ResourceNode parent) {
        return switch (parent.kind()) {
            case DATABASE -> collections(parent.label());
            case COLLECTION -> indexes(dbOf(parent), collOf(parent));
            default -> List.of();
        };
    }

    private List<ResourceNode> collections(String db) {
        service.useDatabase(db);
        List<ResourceNode> out = new ArrayList<>();
        for (String c : service.listCollectionNames()) {
            Map<String, String> details = new LinkedHashMap<>();
            try {
                details.putAll(service.collectionStats(c));
            } catch (Exception ignored) { /* views/system collections may not support collStats */ }
            out.add(new ResourceNode("coll:" + db + "." + c, c,
                    ResourceNode.Kind.COLLECTION, true, details));
        }
        return out;
    }

    private List<ResourceNode> indexes(String db, String collection) {
        service.useDatabase(db);
        List<ResourceNode> out = new ArrayList<>();
        int i = 0;
        for (String json : service.listIndexes(collection)) {
            String name = extract(json, "name", "index_" + i++);
            Map<String, String> details = new LinkedHashMap<>();
            details.put("Keys", extract(json, "key", "{}"));
            details.put("Unique", String.valueOf(json.contains("\"unique\"")));
            details.put("Definition", json);
            out.add(new ResourceNode("idx:" + db + "." + collection + "." + name, name,
                    ResourceNode.Kind.INDEX, false, details));
        }
        return out;
    }

    // "db:foo" / "coll:foo.bar" id helpers (parent ids encode the path)
    private String dbOf(ResourceNode collection) {
        String id = collection.id().substring("coll:".length());
        return id.substring(0, id.indexOf('.'));
    }

    private String collOf(ResourceNode collection) {
        String id = collection.id().substring("coll:".length());
        return id.substring(id.indexOf('.') + 1);
    }

    /** Tiny extractor for a top-level string/object field from shell JSON (no full parse needed). */
    private static String extract(String json, String field, String fallback) {
        String key = "\"" + field + "\"";
        int k = json.indexOf(key);
        if (k < 0) return fallback;
        int colon = json.indexOf(':', k + key.length());
        if (colon < 0) return fallback;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return fallback;
        char c = json.charAt(i);
        if (c == '"') {
            int end = json.indexOf('"', i + 1);
            return end < 0 ? fallback : json.substring(i + 1, end);
        }
        if (c == '{') {
            int depth = 0, j = i;
            do {
                char cj = json.charAt(j);
                if (cj == '{') depth++;
                else if (cj == '}') depth--;
                j++;
            } while (j < json.length() && depth > 0);
            return json.substring(i, j);
        }
        int end = i;
        while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(i, end).trim();
    }
}
