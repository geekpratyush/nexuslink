package com.nexuslink.protocol.kafka;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * A small client for a Confluent-style Schema Registry REST API (list subjects/versions, fetch a
 * schema by subject+version or by id, and register a new schema version). Transport is the JDK
 * {@link HttpClient}; responses are parsed by the dependency-free {@link SchemaRegistryJson}, so the
 * module needs no JSON library. Optional HTTP basic auth is supported.
 */
public final class SchemaRegistryClient {

    /** The Schema Registry content type for request bodies. */
    private static final String CONTENT_TYPE = "application/vnd.schemaregistry.v1+json";

    /** A registered schema version. */
    public record Schema(String subject, int version, int id, String schema) {}

    private final String baseUrl;
    private final String basicAuth;   // pre-encoded "Basic …" header value, or null
    private final HttpClient http;

    public SchemaRegistryClient(String baseUrl) {
        this(baseUrl, null, null);
    }

    public SchemaRegistryClient(String baseUrl, String username, String password) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.basicAuth = (username == null || username.isBlank()) ? null
                : "Basic " + Base64.getEncoder().encodeToString(
                        (username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** GET /subjects → all registered subject names. */
    public List<String> listSubjects() throws IOException {
        Object parsed = SchemaRegistryJson.parse(get("/subjects"));
        return asStringList(parsed);
    }

    /** GET /subjects/{subject}/versions → the version numbers registered under {@code subject}. */
    public List<Integer> listVersions(String subject) throws IOException {
        Object parsed = SchemaRegistryJson.parse(get("/subjects/" + enc(subject) + "/versions"));
        return asIntList(parsed);
    }

    /** GET /subjects/{subject}/versions/{version} → the schema at that subject + version. */
    @SuppressWarnings("unchecked")
    public Schema getSchema(String subject, int version) throws IOException {
        var m = (java.util.Map<String, Object>) SchemaRegistryJson.parse(
                get("/subjects/" + enc(subject) + "/versions/" + version));
        return new Schema(str(m.get("subject")), intVal(m.get("version")), intVal(m.get("id")), str(m.get("schema")));
    }

    /** GET /schemas/ids/{id} → the raw schema string for a global schema id. */
    @SuppressWarnings("unchecked")
    public String getSchemaById(int id) throws IOException {
        var m = (java.util.Map<String, Object>) SchemaRegistryJson.parse(get("/schemas/ids/" + id));
        return str(m.get("schema"));
    }

    /** POST /subjects/{subject}/versions → registers {@code schema} and returns the new global id. */
    @SuppressWarnings("unchecked")
    public int register(String subject, String schema) throws IOException {
        String body = "{\"schema\":" + SchemaRegistryJson.quote(schema) + "}";
        var m = (java.util.Map<String, Object>) SchemaRegistryJson.parse(
                post("/subjects/" + enc(subject) + "/versions", body));
        return intVal(m.get("id"));
    }

    // ---- transport ----

    private String get(String path) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", CONTENT_TYPE)
                .GET();
        if (basicAuth != null) b.header("Authorization", basicAuth);
        return send(b.build());
    }

    private String post(String path, String body) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", CONTENT_TYPE)
                .header("Accept", CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (basicAuth != null) b.header("Authorization", basicAuth);
        return send(b.build());
    }

    private String send(HttpRequest req) throws IOException {
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("Schema Registry " + resp.statusCode() + ": " + resp.body());
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted calling Schema Registry", e);
        }
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object parsed) {
        if (!(parsed instanceof List<?> list)) throw new IllegalArgumentException("Expected a JSON array");
        return ((List<Object>) parsed).stream().map(SchemaRegistryClient::str).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> asIntList(Object parsed) {
        if (!(parsed instanceof List<?> list)) throw new IllegalArgumentException("Expected a JSON array");
        return ((List<Object>) parsed).stream().map(SchemaRegistryClient::intVal).toList();
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static int intVal(Object o) {
        if (o instanceof Number n) return n.intValue();
        throw new IllegalArgumentException("Expected a number, got " + o);
    }

    private static String enc(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String stripTrailingSlash(String url) {
        String u = url == null ? "" : url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
