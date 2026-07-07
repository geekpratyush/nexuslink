package com.nexuslink.protocol.secrets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A thin HashiCorp Vault client over the JDK HTTP client (no heavy HTTP dependency). Covers the
 * pieces an engineer reaches for when testing a Vault: authenticating (direct token or AppRole),
 * a health probe, and full KV v2 secret CRUD (read / write / list / delete).
 *
 * <p>The token is sent in the {@code X-Vault-Token} header; an optional Vault Enterprise
 * {@code namespace} is sent in {@code X-Vault-Namespace}. All calls are blocking and are expected to
 * run off the UI thread. Path building lives in {@link VaultPaths} so it can be unit-tested offline.
 */
public final class VaultService implements AutoCloseable {

    /** A KV v2 secret: its current version number and the string key/value pairs it holds. */
    public record KvSecret(int version, Map<String, String> data) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private String address;      // normalised, no trailing slash
    private String token;
    private String namespace;    // optional (Vault Enterprise)

    // ---- connection / auth --------------------------------------------------------------

    /** Connects with a pre-issued token (e.g. a root or a periodic token). */
    public void connectToken(String address, String token, String namespace) {
        this.address = VaultPaths.normalizeAddress(address);
        if (token == null || token.isBlank()) throw new IllegalArgumentException("token is required");
        this.token = token.trim();
        this.namespace = namespace == null || namespace.isBlank() ? null : namespace.trim();
    }

    /**
     * Authenticates via AppRole ({@code role_id} + {@code secret_id}) against the given auth mount
     * (blank = {@code approle}), stores the returned client token, and returns it. This is the
     * machine-to-machine auth method most CI/service workloads use.
     */
    public String loginAppRole(String address, String roleId, String secretId,
                               String authMount, String namespace) {
        this.address = VaultPaths.normalizeAddress(address);
        this.namespace = namespace == null || namespace.isBlank() ? null : namespace.trim();
        this.token = null;
        ObjectNode body = JSON.createObjectNode();
        body.put("role_id", roleId == null ? "" : roleId.trim());
        body.put("secret_id", secretId == null ? "" : secretId.trim());
        JsonNode resp = request("POST", VaultPaths.approleLoginPath(authMount), body);
        JsonNode clientToken = resp.path("auth").path("client_token");
        if (clientToken.isMissingNode() || clientToken.asText().isBlank()) {
            throw new VaultException("AppRole login returned no client_token");
        }
        this.token = clientToken.asText();
        return this.token;
    }

    public boolean isConnected() { return address != null && token != null; }

    public String address() { return address; }

    /** Returns the raw {@code sys/health} JSON (initialized / sealed / version …). */
    public JsonNode health() {
        return request("GET", "v1/sys/health", null);
    }

    // ---- KV v2 CRUD ---------------------------------------------------------------------

    /** Reads a KV v2 secret; returns its current version and string data map. */
    public KvSecret readKv2(String mount, String path) {
        JsonNode resp = request("GET", VaultPaths.kv2DataPath(mount, path), null);
        JsonNode data = resp.path("data").path("data");
        JsonNode meta = resp.path("data").path("metadata");
        Map<String, String> out = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = data.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            out.put(e.getKey(), e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString());
        }
        return new KvSecret(meta.path("version").asInt(0), out);
    }

    /** Writes (creates a new version of) a KV v2 secret from string key/value pairs. */
    public void writeKv2(String mount, String path, Map<String, String> data) {
        ObjectNode body = JSON.createObjectNode();
        ObjectNode inner = body.putObject("data");
        if (data != null) data.forEach(inner::put);
        request("POST", VaultPaths.kv2DataPath(mount, path), body);
    }

    /** Lists the keys directly under a KV v2 path (folder-style; keys ending in {@code /} are sub-folders). */
    public List<String> listKv2(String mount, String path) {
        JsonNode resp = request("LIST", VaultPaths.kv2MetadataPath(mount, path), null);
        List<String> keys = new ArrayList<>();
        for (JsonNode k : resp.path("data").path("keys")) keys.add(k.asText());
        return keys;
    }

    /** Permanently deletes a KV v2 secret and all its version history (metadata delete). */
    public void deleteKv2(String mount, String path) {
        request("DELETE", VaultPaths.kv2MetadataPath(mount, path), null);
    }

    // ---- generic request seam -----------------------------------------------------------

    /**
     * Issues an authenticated Vault API call and returns the parsed JSON body (an empty object for a
     * 204/empty response). {@code apiPath} is relative to the server address, e.g. {@code v1/sys/health}.
     * Used by the higher-level methods above and available for less-common endpoints (e.g. enabling an
     * auth method in tests). Vault's {@code LIST} verb is sent as a GET with {@code ?list=true} since the
     * JDK client rejects non-standard method names.
     */
    public JsonNode request(String method, String apiPath, JsonNode body) {
        if (address == null) throw new IllegalStateException("not connected");
        String m = method.toUpperCase();
        String url = address + "/" + apiPath;
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(m.equals("LIST") ? url + "?list=true" : url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (token != null) b.header("X-Vault-Token", token);
        if (namespace != null) b.header("X-Vault-Namespace", namespace);

        HttpRequest.BodyPublisher pub = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8);
        if (body != null) b.header("Content-Type", "application/json");

        switch (m) {
            case "GET", "LIST" -> b.GET();
            case "POST", "PUT" -> b.method("POST", pub);
            case "DELETE" -> b.DELETE();
            default -> b.method(m, pub);
        }

        HttpResponse<String> resp;
        try {
            resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new VaultException("Vault request failed: " + e.getMessage(), e);
        }
        int sc = resp.statusCode();
        String bodyStr = resp.body();
        if (sc < 200 || sc >= 300) {
            throw new VaultException("Vault " + m + " " + apiPath + " -> HTTP " + sc
                    + (bodyStr == null || bodyStr.isBlank() ? "" : ": " + bodyStr.strip()));
        }
        if (bodyStr == null || bodyStr.isBlank()) return JSON.createObjectNode();
        try {
            return JSON.readTree(bodyStr);
        } catch (Exception e) {
            throw new VaultException("Vault returned non-JSON body: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        token = null;
        address = null;
        namespace = null;
    }

    /** Unchecked wrapper for any Vault transport/API error, so callers catch one type. */
    public static final class VaultException extends RuntimeException {
        public VaultException(String message) { super(message); }
        public VaultException(String message, Throwable cause) { super(message, cause); }
    }
}
