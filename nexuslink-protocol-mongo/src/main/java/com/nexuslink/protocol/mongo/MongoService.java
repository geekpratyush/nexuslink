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
