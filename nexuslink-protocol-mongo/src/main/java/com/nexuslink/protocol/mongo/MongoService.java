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
    // Read once per connection: which product, version and topology we are actually talking to.
    private MongoServerInfo serverInfo;

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

    /**
     * A find with the full Compass query bar: filter, projection, sort, skip and limit. Every part is
     * Extended JSON and may be blank. Documents come back decoded as well as rendered, so the tree
     * view can show real BSON types rather than re-parsing the JSON it just printed.
     */
    public MongoFindResult findDetailed(String collection, MongoQuerySpec spec) {
        long start = System.nanoTime();
        try {
            var cursor = collection(collection).find(parseFilter(spec.filter()));
            if (!spec.projection().isBlank()) cursor = cursor.projection(Document.parse(spec.projection()));
            if (!spec.sort().isBlank()) cursor = cursor.sort(Document.parse(spec.sort()));
            if (spec.skip() > 0) cursor = cursor.skip(spec.skip());
            cursor = cursor.limit(spec.limit());

            List<Document> documents = new ArrayList<>();
            List<String> json = new ArrayList<>();
            for (Document d : cursor) {
                documents.add(d);
                json.add(d.toJson(SHELL));
            }
            return MongoFindResult.ok(documents, json, ms(start));
        } catch (Exception e) {
            return MongoFindResult.error(e.getMessage(), ms(start));
        }
    }

    /**
     * Sets one field of one document, by {@code _id} and dotted path, to an already-typed value —
     * the save behind an in-place tree edit. Unlike replacing the document it touches nothing else,
     * so a concurrent edit to another field is not silently reverted.
     *
     * @return the number of documents modified (0 when the value was already that)
     */
    public long setField(String collection, Object id, String path, Object value) {
        return collection(collection)
                .updateOne(new Document("_id", id), BsonValueParser.setUpdate(path, value))
                .getModifiedCount();
    }

    /** Removes one field from one document, by {@code _id} and dotted path. */
    public long unsetField(String collection, Object id, String path) {
        return collection(collection)
                .updateOne(new Document("_id", id), BsonValueParser.unsetUpdate(path))
                .getModifiedCount();
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

    /**
     * Runs one {@code mongosh}-style line — the shell tab. Parsing is
     * {@link MongoShellCommand}'s job; this method maps a parsed command onto the driver and renders
     * whatever came back, so a read prints documents, a write prints its counts, and a line outside
     * the grammar prints the reason rather than failing silently.
     */
    public MongoQueryResult runShell(String line) {
        long start = System.nanoTime();
        MongoShellCommand cmd = MongoShellCommand.parse(line);
        if (!cmd.isRunnable()) return MongoQueryResult.error(cmd.unsupportedReason(), ms(start));
        try {
            if (cmd.isDatabaseLevel()) return databaseHelper(cmd, start);

            String c = cmd.collection();
            // A trailing .count() turns a read into its count, as it does in the shell.
            if (cmd.count() && cmd.isRead()) {
                return line(String.valueOf(countDocuments(c, cmd.firstArgument())), start);
            }
            return switch (cmd.operation()) {
                case "find" -> findDetailed(c, cmd.toQuerySpec(50)).asQueryResult();
                case "findone" -> {
                    MongoFindResult r = findDetailed(c, cmd.toQuerySpec(1).withFilter(cmd.firstArgument()));
                    yield r.asQueryResult();
                }
                case "aggregate" -> aggregate(c, cmd.firstArgument());
                case "distinct" -> {
                    // BsonValue, not Object: the driver's registry has no codec for Object, and the
                    // field's values may be of mixed BSON types anyway.
                    String field = stripQuotes(cmd.firstArgument());
                    Bson filter = cmd.secondArgument() == null
                            ? new Document() : parseFilter(cmd.secondArgument());
                    List<String> values = new ArrayList<>();
                    for (BsonValue v : collection(c).distinct(field, filter, BsonValue.class)) {
                        values.add(renderBson(v));
                    }
                    yield MongoQueryResult.ok(values, ms(start));
                }
                case "count", "countdocuments" ->
                        line(String.valueOf(countDocuments(c, cmd.firstArgument())), start);
                case "insertone" -> line("inserted _id: " + insertOne(c, cmd.firstArgument()), start);
                case "insertmany" -> {
                    List<Document> docs = new ArrayList<>();
                    for (BsonValue v : BsonArray.parse(cmd.firstArgument())) {
                        docs.add(Document.parse(v.asDocument().toJson()));
                    }
                    collection(c).insertMany(docs);
                    yield line("inserted " + docs.size() + " document(s)", start);
                }
                case "updateone" -> line(collection(c).updateOne(parseFilter(cmd.firstArgument()),
                        Document.parse(requireSecond(cmd, "updateOne"))).getModifiedCount()
                        + " document(s) modified", start);
                case "updatemany" -> line(updateMany(c, cmd.firstArgument(),
                        requireSecond(cmd, "updateMany")) + " document(s) modified", start);
                case "replaceone" -> line(collection(c).replaceOne(parseFilter(cmd.firstArgument()),
                        Document.parse(requireSecond(cmd, "replaceOne"))).getModifiedCount()
                        + " document(s) replaced", start);
                case "deleteone" -> line(collection(c).deleteOne(parseFilter(cmd.firstArgument()))
                        .getDeletedCount() + " document(s) deleted", start);
                case "deletemany" -> line(deleteMany(c, cmd.firstArgument()) + " document(s) deleted", start);
                case "drop" -> {
                    collection(c).drop();
                    yield line("collection " + c + " dropped", start);
                }
                case "createindex" -> line("index created: "
                        + collection(c).createIndex(Document.parse(cmd.firstArgument())), start);
                case "dropindex" -> {
                    collection(c).dropIndex(stripQuotes(cmd.firstArgument()));
                    yield line("index dropped", start);
                }
                case "getindexes" -> {
                    List<String> out = new ArrayList<>();
                    for (Document d : collection(c).listIndexes()) out.add(d.toJson(SHELL));
                    yield MongoQueryResult.ok(out, ms(start));
                }
                case "stats" -> {
                    List<String> out = new ArrayList<>();
                    collectionStats(c).forEach((k, v) -> out.add(k + ": " + v));
                    yield MongoQueryResult.ok(out, ms(start));
                }
                case "explain" -> line(explain(c, cmd.firstArgument()), start);
                default -> MongoQueryResult.error("Unsupported operation: " + cmd.operation(), ms(start));
            };
        } catch (Exception e) {
            return MongoQueryResult.error(e.getMessage(), ms(start));
        }
    }

    /** The database-level shell helpers: {@code db.getCollectionNames()}, {@code db.stats()}, … */
    private MongoQueryResult databaseHelper(MongoShellCommand cmd, long start) {
        return switch (cmd.operation()) {
            case "getcollectionnames" -> MongoQueryResult.ok(listCollectionNames(), ms(start));
            case "getcollectioninfos" -> {
                List<String> out = new ArrayList<>();
                for (Document d : db().listCollections()) out.add(d.toJson(SHELL));
                yield MongoQueryResult.ok(out, ms(start));
            }
            case "stats" -> MongoQueryResult.ok(
                    List.of(db().runCommand(new Document("dbStats", 1)).toJson(SHELL)), ms(start));
            case "version" -> MongoQueryResult.ok(List.of(serverInfo().version()), ms(start));
            default -> MongoQueryResult.error("Unsupported helper: " + cmd.operation(), ms(start));
        };
    }

    /** A one-line result, for the write operations that report a count rather than documents. */
    private MongoQueryResult line(String text, long start) {
        return MongoQueryResult.ok(List.of(text), ms(start));
    }

    private static String requireSecond(MongoShellCommand cmd, String operation) {
        String second = cmd.secondArgument();
        if (second == null) {
            throw new IllegalArgumentException(operation + " needs two arguments: a filter and an update");
        }
        return second;
    }

    /** A distinct value rendered plainly: a string without its quotes, anything else as JSON. */
    private static String renderBson(BsonValue value) {
        if (value == null || value.isNull()) return "null";
        if (value.isString()) return value.asString().getValue();
        if (value.isNumber()) return String.valueOf(value.asNumber().doubleValue() % 1 == 0
                ? value.asNumber().longValue() : value.asNumber().doubleValue());
        if (value.isBoolean()) return String.valueOf(value.asBoolean().getValue());
        return value.toString();
    }

    /** {@code "name"} → {@code name}, for the shell arguments that are plain strings. */
    private static String stripQuotes(String s) {
        String t = s == null ? "" : s.trim();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'"))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
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

    /** Drops a single index by name ({@code _id_} cannot be dropped — Mongo rejects it). */
    public void dropIndex(String collection, String indexName) {
        collection(collection).dropIndex(indexName);
    }

    /** Index names on a collection, in server order (includes the implicit {@code _id_}). */
    public List<String> indexNames(String collection) {
        List<String> out = new ArrayList<>();
        for (Document d : collection(collection).listIndexes()) {
            out.add(String.valueOf(d.get("name")));
        }
        return out;
    }

    // ---- users / auth ----

    /**
     * Exports a whole collection to a file, streaming rather than loading it into memory: documents
     * are written as they arrive from the cursor, so a collection larger than the heap still exports.
     *
     * <p>CSV needs a header before the first row, so the column set is taken from a bounded sample
     * first ({@code headerSample} documents) — otherwise it could only be known after reading
     * everything. Fields outside that sample are not exported to CSV; JSON has no such limit.
     *
     * @param progress called with the running document count, for a progress bar
     * @return the number of documents written
     */
    public long exportCollection(String collection, String filterJson, CollectionTransfer.Format format,
                                 java.nio.file.Path file, int headerSample,
                                 java.util.function.LongConsumer progress) throws java.io.IOException {
        Bson filter = parseFilter(filterJson);
        List<String> columns = List.of();
        if (format == CollectionTransfer.Format.CSV) {
            List<Document> sample = new ArrayList<>();
            for (Document d : collection(collection).find(filter).limit(Math.max(1, headerSample))) {
                sample.add(d);
            }
            columns = CollectionTransfer.columnsOf(sample);
        }

        long written = 0;
        try (java.io.BufferedWriter out = java.nio.file.Files.newBufferedWriter(file,
                java.nio.charset.StandardCharsets.UTF_8)) {
            if (format == CollectionTransfer.Format.CSV) {
                out.write(CollectionTransfer.toCsvHeader(columns));
                out.write("\n");
            } else if (format == CollectionTransfer.Format.JSON_ARRAY) {
                out.write("[\n");
            }
            boolean first = true;
            for (Document d : collection(collection).find(filter)) {
                switch (format) {
                    case CSV -> { out.write(CollectionTransfer.toCsvRow(d, columns)); out.write("\n"); }
                    case JSON_LINES -> { out.write(CollectionTransfer.toJsonLine(d)); out.write("\n"); }
                    case JSON_ARRAY -> {
                        if (!first) out.write(",\n");
                        out.write("  ");
                        out.write(CollectionTransfer.toJsonLine(d));
                    }
                }
                first = false;
                written++;
                if (progress != null && written % 500 == 0) progress.accept(written);
            }
            if (format == CollectionTransfer.Format.JSON_ARRAY) out.write("\n]\n");
        }
        if (progress != null) progress.accept(written);
        return written;
    }

    /**
     * Inserts documents in batches, reporting progress as it goes. Batching is what makes an import
     * of any size finish in reasonable time; the batch is deliberately unordered so one bad document
     * does not stop the rest.
     *
     * @return the number of documents inserted
     */
    public long importDocuments(String collection, List<Document> documents, int batchSize,
                                java.util.function.LongConsumer progress) {
        if (documents == null || documents.isEmpty()) return 0;
        int size = Math.max(1, Math.min(batchSize, 10_000));
        var options = new com.mongodb.client.model.InsertManyOptions().ordered(false);
        long inserted = 0;
        for (int i = 0; i < documents.size(); i += size) {
            List<Document> batch = documents.subList(i, Math.min(documents.size(), i + size));
            collection(collection).insertMany(batch, options);
            inserted += batch.size();
            if (progress != null) progress.accept(inserted);
        }
        return inserted;
    }

    /**
     * Samples up to {@code sampleSize} documents and profiles their shape — the schema analyser.
     * Sampling uses {@code $sample} so it is spread across the collection rather than being the first
     * page, which is what makes an optional field show up at all.
     */
    public SchemaProfile profileSchema(String collection, int sampleSize) {
        int size = Math.max(1, Math.min(sampleSize, 10_000));
        List<Document> sample = new ArrayList<>();
        for (Document d : collection(collection)
                .aggregate(List.of(new Document("$sample", new Document("size", size))))) {
            sample.add(d);
        }
        return SchemaProfile.of(sample);
    }

    /**
     * {@code $indexStats} for a collection — how many operations have used each index since the
     * server's counters started. Empty when the deployment does not expose it (some managed services
     * do not).
     */
    public List<Document> indexStats(String collection) {
        List<Document> out = new ArrayList<>();
        try {
            for (Document d : collection(collection)
                    .aggregate(List.of(new Document("$indexStats", new Document())))) {
                out.add(d);
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return out;
    }

    /** The key documents of a collection's indexes, for the "is this already covered" check. */
    public List<Document> indexKeys(String collection) {
        List<Document> keys = new ArrayList<>();
        for (Document d : collection(collection).listIndexes()) {
            Document key = d.get("key", Document.class);
            if (key != null) keys.add(key);
        }
        return keys;
    }

    /**
     * The index the given find would want, or an empty string when the existing indexes already
     * serve it. Filter and sort are Extended JSON, as typed in the query bar.
     */
    public String indexRecommendation(String collection, String filterJson, String sortJson) {
        try {
            Document filter = filterJson == null || filterJson.isBlank()
                    ? new Document() : Document.parse(filterJson);
            Document sort = sortJson == null || sortJson.isBlank()
                    ? new Document() : Document.parse(sortJson);
            return IndexAdvice.recommendation(filter, sort, indexKeys(collection));
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** {@code connectionStatus} — the authenticated user and its roles, or empty when unauthenticated. */
    public java.util.Map<String, String> authStatus() {
        Document r = client.getDatabase("admin").runCommand(new Document("connectionStatus", 1));
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        Document info = (Document) r.get("authInfo");
        List<?> users = info == null ? List.of() : (List<?>) info.getOrDefault("authenticatedUsers", List.of());
        List<?> roles = info == null ? List.of() : (List<?>) info.getOrDefault("authenticatedUserRoles", List.of());
        if (users.isEmpty()) {
            out.put("Authenticated as", "(none — unauthenticated connection)");
        } else {
            StringBuilder names = new StringBuilder();
            for (Object u : users) {
                Document d = (Document) u;
                if (names.length() > 0) names.append(", ");
                names.append(d.get("user")).append("@").append(d.get("db"));
            }
            out.put("Authenticated as", names.toString());
        }
        StringBuilder rs = new StringBuilder();
        for (Object o : roles) {
            Document d = (Document) o;
            if (rs.length() > 0) rs.append(", ");
            rs.append(d.get("role")).append("@").append(d.get("db"));
        }
        out.put("Roles", rs.length() == 0 ? "(none)" : rs.toString());
        return out;
    }

    /** Users defined on {@code database}, rendered as "name — role@db, role@db". */
    public List<String> listUsers(String database) {
        if (!serverInfo().supportsUserAdministration()) {
            throw new UnsupportedOperationException(
                    serverInfo().product() + " does not expose user administration through the driver");
        }
        Document r = client.getDatabase(database).runCommand(new Document("usersInfo", 1));
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) r.getOrDefault("users", List.of())) {
            Document u = (Document) o;
            StringBuilder sb = new StringBuilder(String.valueOf(u.get("user")));
            List<?> roles = (List<?>) u.getOrDefault("roles", List.of());
            if (!roles.isEmpty()) {
                sb.append("  —  ");
                for (int i = 0; i < roles.size(); i++) {
                    Document rd = (Document) roles.get(i);
                    if (i > 0) sb.append(", ");
                    sb.append(rd.get("role")).append("@").append(rd.get("db"));
                }
            }
            out.add(sb.toString());
        }
        return out;
    }

    /**
     * Creates a user on {@code database}. {@code roles} is a comma-separated list where each
     * entry is {@code role} (on this database) or {@code role@db}.
     */
    public void createUser(String database, String user, String password, String roles) {
        Document cmd = new Document("createUser", user).append("pwd", password)
                .append("roles", parseRoles(database, roles));
        client.getDatabase(database).runCommand(cmd);
    }

    /** Replaces a user's roles (same syntax as {@link #createUser}). */
    public void grantRoles(String database, String user, String roles) {
        client.getDatabase(database).runCommand(
                new Document("updateUser", user).append("roles", parseRoles(database, roles)));
    }

    public void dropUser(String database, String user) {
        client.getDatabase(database).runCommand(new Document("dropUser", user));
    }

    private static List<Document> parseRoles(String defaultDb, String roles) {
        List<Document> out = new ArrayList<>();
        if (roles == null || roles.isBlank()) return out;
        for (String raw : roles.split(",")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            int at = s.indexOf('@');
            out.add(at < 0
                    ? new Document("role", s).append("db", defaultDb)
                    : new Document("role", s.substring(0, at).trim()).append("db", s.substring(at + 1).trim()));
        }
        return out;
    }

    /**
     * The deployment's own description of itself — product, version and topology — read once per
     * connection from {@code buildInfo} and {@code hello}. Drives which commands this client uses.
     */
    public MongoServerInfo serverInfo() {
        if (serverInfo != null) return serverInfo;
        if (client == null) return MongoServerInfo.unknown();
        Document build = null;
        Document hello = null;
        try {
            build = client.getDatabase("admin").runCommand(new Document("buildInfo", 1));
        } catch (RuntimeException ignored) {
            // A locked-down deployment may refuse buildInfo; the version simply stays unknown.
        }
        try {
            hello = client.getDatabase("admin").runCommand(new Document("hello", 1));
        } catch (RuntimeException e) {
            try {   // Pre-5.0 servers (and some imitations) only know the old name.
                hello = client.getDatabase("admin").runCommand(new Document("isMaster", 1));
            } catch (RuntimeException ignored) { }
        }
        serverInfo = MongoServerInfo.of(build, hello);
        return serverInfo;
    }

    /**
     * Collection statistics for the details panel.
     *
     * <p>Read with the {@code $collStats} aggregation stage on 6.2+, where the {@code collStats}
     * command is deprecated and shared Atlas tiers refuse it outright; older servers get the command.
     * If the preferred route fails the other one is tried, so a deployment that reports a version it
     * does not honour still shows its numbers.
     */
    public java.util.Map<String, String> collectionStats(String collection) {
        if (serverInfo().prefersCollStatsAggregation()) {
            try {
                return collectionStatsViaAggregation(collection);
            } catch (RuntimeException e) {
                return collectionStatsViaCommand(collection);   // fall back to the deprecated command
            }
        }
        try {
            return collectionStatsViaCommand(collection);
        } catch (RuntimeException e) {
            return collectionStatsViaAggregation(collection);
        }
    }

    /** {@code $collStats} — the supported route from 6.2 on. */
    private java.util.Map<String, String> collectionStatsViaAggregation(String collection) {
        Document stage = new Document("$collStats",
                new Document("storageStats", new Document()).append("count", new Document()));
        Document result = collection(collection).aggregate(List.of(stage)).first();
        if (result == null) throw new IllegalStateException("$collStats returned nothing");
        Document storage = (Document) result.getOrDefault("storageStats", new Document());
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        Object count = storage.get("count") != null ? storage.get("count") : result.get("count");
        out.put("Documents", String.valueOf(count));
        out.put("Storage size", humanBytes(toLong(storage.get("storageSize"))));
        out.put("Data size", humanBytes(toLong(storage.get("size"))));
        out.put("Avg doc size", humanBytes(toLong(storage.get("avgObjSize"))));
        out.put("Indexes", String.valueOf(storage.get("nindexes")));
        out.put("Total index size", humanBytes(toLong(storage.get("totalIndexSize"))));
        out.put("Capped", String.valueOf(storage.getOrDefault("capped", false)));
        return out;
    }

    /** The legacy {@code collStats} command, for servers older than 6.2. */
    private java.util.Map<String, String> collectionStatsViaCommand(String collection) {
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
        serverInfo = null;   // belongs to the connection being dropped
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
