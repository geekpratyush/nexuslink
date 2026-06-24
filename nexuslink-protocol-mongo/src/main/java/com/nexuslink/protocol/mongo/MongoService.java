package com.nexuslink.protocol.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.BsonArray;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonWriterSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB client built on the official synchronous driver. Connect with a standard
 * connection string ({@code mongodb://host:port} or {@code mongodb+srv://…}), then browse
 * databases/collections and run find / aggregate / CRUD operations.
 * <p>
 * Blocking by design — callers run it off the UI thread. Filters and pipelines are
 * supplied as MongoDB Extended-JSON strings and parsed with the driver's own parser.
 */
public final class MongoService implements AutoCloseable {

    private static final JsonWriterSettings SHELL = JsonWriterSettings.builder().indent(true).build();

    private MongoClient client;
    private String currentDb;

    /** Opens a client and verifies connectivity by listing database names. */
    public List<String> connect(String connectionString) {
        close();
        this.client = MongoClients.create(connectionString);
        return listDatabaseNames(); // forces a round-trip; throws if unreachable
    }

    public boolean isConnected() {
        return client != null;
    }

    public void useDatabase(String db) {
        this.currentDb = db;
    }

    public String currentDatabase() {
        return currentDb;
    }

    public List<String> listDatabaseNames() {
        List<String> names = new ArrayList<>();
        client.listDatabaseNames().forEach(names::add);
        return names;
    }

    public List<String> listCollectionNames() {
        List<String> names = new ArrayList<>();
        db().listCollectionNames().forEach(names::add);
        return names;
    }

    /** find(filter) with a result cap. {@code filterJson} may be blank/{} for all docs. */
    public MongoQueryResult find(String collection, String filterJson, int limit) {
        long start = System.nanoTime();
        try {
            Bson filter = parseFilter(filterJson);
            List<String> docs = new ArrayList<>();
            for (Document d : collection(collection).find(filter).limit(limit)) {
                docs.add(d.toJson(SHELL));
            }
            return MongoQueryResult.ok(docs, ms(start));
        } catch (Exception e) {
            return MongoQueryResult.error(e.getMessage(), ms(start));
        }
    }

    /** aggregate(pipeline) — pipeline is a JSON array of stage documents. */
    public MongoQueryResult aggregate(String collection, String pipelineJson) {
        long start = System.nanoTime();
        try {
            List<Bson> pipeline = new ArrayList<>();
            for (BsonValue stage : BsonArray.parse(pipelineJson)) {
                pipeline.add(stage.asDocument());
            }
            List<String> docs = new ArrayList<>();
            for (Document d : collection(collection).aggregate(pipeline)) {
                docs.add(d.toJson(SHELL));
            }
            return MongoQueryResult.ok(docs, ms(start));
        } catch (Exception e) {
            return MongoQueryResult.error(e.getMessage(), ms(start));
        }
    }

    public long countDocuments(String collection, String filterJson) {
        return collection(collection).countDocuments(parseFilter(filterJson));
    }

    /** Inserts one document; returns its _id as a string. */
    public String insertOne(String collection, String docJson) {
        Document doc = Document.parse(docJson);
        collection(collection).insertOne(doc);
        Object id = doc.get("_id");
        return id == null ? "(generated)" : id.toString();
    }

    /** updateMany($set semantics expected in updateJson); returns modified count. */
    public long updateMany(String collection, String filterJson, String updateJson) {
        return collection(collection)
                .updateMany(parseFilter(filterJson), Document.parse(updateJson))
                .getModifiedCount();
    }

    public long deleteMany(String collection, String filterJson) {
        return collection(collection).deleteMany(parseFilter(filterJson)).getDeletedCount();
    }

    private static final java.util.regex.Pattern SELECT_SQL = java.util.regex.Pattern.compile(
            "(?is)^SELECT\\s+(.+?)\\s+FROM\\s+([\\w.]+)" +
            "(?:\\s+WHERE\\s+(.+?))?(?:\\s+ORDER\\s+BY\\s+(.+?))?(?:\\s+LIMIT\\s+(\\d+))?\\s*;?$");
    private static final java.util.regex.Pattern CONDITION = java.util.regex.Pattern.compile(
            "(?i)^\\s*([\\w.]+)\\s*(>=|<=|!=|=|>|<|LIKE)\\s*(.+?)\\s*$");

    /**
     * Runs a SQL-like SELECT against a collection and returns matching documents. Supported:
     * {@code SELECT <cols|*> FROM <collection> [WHERE c AND c …] [ORDER BY f [ASC|DESC]] [LIMIT n]}
     * with operators = != &gt; &lt; &gt;= &lt;= LIKE.
     */
    public MongoQueryResult executeSql(String sql) {
        long start = System.nanoTime();
        try {
            java.util.regex.Matcher m = SELECT_SQL.matcher(sql.trim());
            if (!m.matches()) {
                return MongoQueryResult.error(
                        "Unsupported SQL. Use: SELECT <cols|*> FROM <collection> [WHERE …] [ORDER BY …] [LIMIT n]",
                        ms(start));
            }
            String coll = m.group(2).trim();
            Bson filter = m.group(3) == null ? new Document() : parseWhere(m.group(3));
            Document projection = parseProjection(m.group(1).trim());
            Document sort = m.group(4) == null ? null : parseOrder(m.group(4));
            int limit = m.group(5) == null ? 100 : Integer.parseInt(m.group(5));

            var find = collection(coll).find(filter);
            if (projection != null) find = find.projection(projection);
            if (sort != null) find = find.sort(sort);
            List<String> docs = new ArrayList<>();
            for (Document d : find.limit(limit)) docs.add(d.toJson(SHELL));
            return MongoQueryResult.ok(docs, ms(start));
        } catch (Exception e) {
            return MongoQueryResult.error(e.getMessage(), ms(start));
        }
    }

    static Document parseProjection(String cols) {
        if (cols.equals("*")) return null;
        Document p = new Document();
        for (String c : cols.split(",")) if (!c.isBlank()) p.put(c.trim(), 1);
        return p;
    }

    static Document parseWhere(String where) {
        Document filter = new Document();
        for (String clause : where.split("(?i)\\s+AND\\s+")) {
            java.util.regex.Matcher m = CONDITION.matcher(clause);
            if (!m.matches()) throw new IllegalArgumentException("Bad condition: " + clause.trim());
            String field = m.group(1);
            String op = m.group(2).toUpperCase();
            Object value = parseValue(m.group(3));
            switch (op) {
                case "=" -> filter.put(field, value);
                case "!=" -> filter.put(field, new Document("$ne", value));
                case ">" -> filter.put(field, new Document("$gt", value));
                case "<" -> filter.put(field, new Document("$lt", value));
                case ">=" -> filter.put(field, new Document("$gte", value));
                case "<=" -> filter.put(field, new Document("$lte", value));
                case "LIKE" -> filter.put(field, new Document("$regex",
                        "^" + java.util.regex.Pattern.quote(String.valueOf(value)).replace("%", "\\E.*\\Q") + "$")
                        .append("$options", "i"));
                default -> throw new IllegalArgumentException("Unsupported operator: " + op);
            }
        }
        return filter;
    }

    static Document parseOrder(String order) {
        Document sort = new Document();
        for (String part : order.split(",")) {
            String[] tk = part.trim().split("\\s+");
            sort.put(tk[0], tk.length > 1 && tk[1].equalsIgnoreCase("DESC") ? -1 : 1);
        }
        return sort;
    }

    private static Object parseValue(String raw) {
        String v = raw.trim();
        if ((v.startsWith("'") && v.endsWith("'")) || (v.startsWith("\"") && v.endsWith("\""))) {
            return v.substring(1, v.length() - 1);
        }
        try { return Integer.parseInt(v); } catch (NumberFormatException ignored) { }
        try { return Long.parseLong(v); } catch (NumberFormatException ignored) { }
        try { return Double.parseDouble(v); } catch (NumberFormatException ignored) { }
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) return Boolean.parseBoolean(v);
        return v;
    }

    /**
     * Infers a schema for each collection in {@code database} by sampling documents, then renders a
     * Mermaid {@code erDiagram} — entities = collections (fields + inferred BSON types), with
     * relationships guessed from {@code <name>_id} / {@code <name>Id} fields that match a collection.
     */
    public String inferDiagram(String database, int sampleSize) {
        useDatabase(database);
        List<String> collections = listCollectionNames();
        java.util.Map<String, java.util.LinkedHashMap<String, String>> schema = new java.util.LinkedHashMap<>();
        for (String c : collections) {
            java.util.LinkedHashMap<String, String> fields = new java.util.LinkedHashMap<>();
            for (Document d : collection(c).find().limit(Math.max(1, sampleSize))) {
                for (String k : d.keySet()) fields.putIfAbsent(k, bsonType(d.get(k)));
            }
            schema.put(c, fields);
        }

        StringBuilder sb = new StringBuilder("erDiagram\n");
        java.util.LinkedHashSet<String> rels = new java.util.LinkedHashSet<>();
        for (var entry : schema.entrySet()) {
            String child = entry.getKey();
            for (String field : entry.getValue().keySet()) {
                String base = referenceBase(field);
                if (base == null) continue;
                String target = matchCollection(base, collections);
                if (target != null && !target.equals(child)) {
                    rels.add(safe(target) + " ||--o{ " + safe(child) + " : ref");
                }
            }
        }
        for (String r : rels) sb.append("  ").append(r).append('\n');
        for (var entry : schema.entrySet()) {
            sb.append("  ").append(safe(entry.getKey())).append(" {\n");
            for (var f : entry.getValue().entrySet()) {
                String key = "_id".equals(f.getKey()) ? " PK" : referenceBase(f.getKey()) != null ? " FK" : "";
                sb.append("    ").append(f.getValue()).append(' ').append(safe(f.getKey())).append(key).append('\n');
            }
            sb.append("  }\n");
        }
        return sb.toString();
    }

    private static String bsonType(Object v) {
        if (v == null) return "null";
        if (v instanceof org.bson.types.ObjectId) return "objectId";
        if (v instanceof String) return "string";
        if (v instanceof Integer) return "int";
        if (v instanceof Long) return "long";
        if (v instanceof Double || v instanceof java.math.BigDecimal) return "double";
        if (v instanceof Boolean) return "bool";
        if (v instanceof java.util.Date) return "date";
        if (v instanceof List) return "array";
        if (v instanceof Document) return "object";
        return "mixed";
    }

    /** Returns the referenced base name for a foreign-key-style field, or null. */
    private static String referenceBase(String field) {
        if (field.equals("_id")) return null;
        if (field.endsWith("_id")) return field.substring(0, field.length() - 3);
        if (field.length() > 2 && field.endsWith("Id")) return field.substring(0, field.length() - 2);
        return null;
    }

    private static String matchCollection(String base, List<String> collections) {
        for (String suffix : new String[]{"", "s", "es"}) {
            String candidate = base + suffix;
            for (String c : collections) if (c.equalsIgnoreCase(candidate)) return c;
        }
        return null;
    }

    private static String safe(String name) {
        String s = name.replaceAll("[^A-Za-z0-9_]", "_");
        return s.isEmpty() ? "_" : s;
    }

    /** Replaces the document with {@code id} by the parsed {@code newJson}; returns modified count. */
    public long replaceById(String collection, Object id, String newJson) {
        return collection(collection)
                .replaceOne(new Document("_id", id), Document.parse(newJson))
                .getModifiedCount();
    }

    /** Deletes the document with {@code id}; returns deleted count. */
    public long deleteById(String collection, Object id) {
        return collection(collection).deleteOne(new Document("_id", id)).getDeletedCount();
    }

    /** Returns the query plan (explain output) for a find filter as shell JSON. */
    public String explain(String collection, String filterJson) {
        return collection(collection).find(parseFilter(filterJson)).explain().toJson(SHELL);
    }

    /** Serializes documents to a JSON array (for export). */
    public static String toJsonArray(List<Document> docs) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < docs.size(); i++) {
            sb.append("  ").append(docs.get(i).toJson());
            if (i < docs.size() - 1) sb.append(',');
            sb.append('\n');
        }
        return sb.append(']').toString();
    }

    /** Serializes documents to CSV using the union of top-level fields as columns. */
    public static String toCsv(List<Document> docs) {
        java.util.LinkedHashSet<String> cols = new java.util.LinkedHashSet<>();
        for (Document d : docs) cols.addAll(d.keySet());
        StringBuilder sb = new StringBuilder();
        sb.append(cols.stream().map(MongoService::csvEscape).collect(java.util.stream.Collectors.joining(",")));
        sb.append('\n');
        for (Document d : docs) {
            java.util.List<String> row = new java.util.ArrayList<>();
            for (String c : cols) row.add(csvEscape(valueString(d.get(c))));
            sb.append(String.join(",", row)).append('\n');
        }
        return sb.toString();
    }

    /** Flattens a BSON value to a single cell string (nested values become compact JSON). */
    public static String valueString(Object v) {
        if (v == null) return "";
        if (v instanceof Document d) return d.toJson();
        if (v instanceof List<?> l) return l.toString();
        return v.toString();
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    /** Creates a new (empty) collection in the current database. */
    public void createCollection(String name) {
        db().createCollection(name);
    }

    /** Creates an index from a keys spec like {@code {"field": 1}}; returns the index name. */
    public String createIndex(String collection, String keysJson, boolean unique) {
        org.bson.Document keys = org.bson.Document.parse(keysJson);
        com.mongodb.client.model.IndexOptions opts = new com.mongodb.client.model.IndexOptions().unique(unique);
        return collection(collection).createIndex(keys, opts);
    }

    /** Lists indexes on a collection as shell-style JSON (one entry per index). */
    public List<String> listIndexes(String collection) {
        List<String> out = new ArrayList<>();
        for (Document d : collection(collection).listIndexes()) {
            out.add(d.toJson(SHELL));
        }
        return out;
    }

    /** Runs {@code collStats} and returns the headline figures used in the details panel. */
    public java.util.Map<String, String> collectionStats(String collection) {
        Document stats = db().runCommand(new Document("collStats", collection));
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        out.put("Documents", String.valueOf(stats.get("count")));
        out.put("Storage size", humanBytes(toLong(stats.get("storageSize"))));
        out.put("Data size", humanBytes(toLong(stats.get("size"))));
        out.put("Avg doc size", humanBytes(toLong(stats.get("avgObjSize"))));
        out.put("Indexes", String.valueOf(stats.get("nindexes")));
        out.put("Total index size", humanBytes(toLong(stats.get("totalIndexSize"))));
        out.put("Capped", String.valueOf(stats.getOrDefault("capped", false)));
        return out;
    }

    private static long toLong(Object o) { return (o instanceof Number n) ? n.longValue() : 0L; }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        do { v /= 1024.0; i++; } while (v >= 1024 && i < units.length - 1);
        return String.format("%.1f %s", v, units[i]);
    }

    // ---- internals ----

    private MongoDatabase db() {
        if (currentDb == null) throw new IllegalStateException("No database selected");
        return client.getDatabase(currentDb);
    }

    private MongoCollection<Document> collection(String name) {
        return db().getCollection(name);
    }

    private Bson parseFilter(String json) {
        return (json == null || json.isBlank()) ? new Document() : Document.parse(json);
    }

    private long ms(long startNanos) {
        return Math.round((System.nanoTime() - startNanos) / 1_000_000.0);
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
