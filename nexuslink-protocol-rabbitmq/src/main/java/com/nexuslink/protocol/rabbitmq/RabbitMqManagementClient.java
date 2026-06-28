package com.nexuslink.protocol.rabbitmq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Client for the RabbitMQ HTTP management API (the plugin served on port {@code 15672} at
 * {@code /api}). Builds the request paths, attaches HTTP Basic auth, and parses the JSON replies
 * into the small {@link OverviewInfo}/{@link QueueInfo}/{@link ExchangeInfo}/{@link BindingInfo}
 * records.
 *
 * <p>All URL building, vhost encoding, auth-header formatting, and JSON parsing are exposed as
 * {@code static} helpers so they can be unit-tested without a running broker.
 */
public final class RabbitMqManagementClient {

    /** Default RabbitMQ management plugin port. */
    public static final int DEFAULT_PORT = 15672;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiBase;
    private final String authHeader;
    private final HttpClient http;

    /** Connects to {@code http://host:15672/api} with the supplied credentials. */
    public RabbitMqManagementClient(String host, String user, String password) {
        this(apiBase(host, DEFAULT_PORT), user, password, true);
    }

    /** Connects to {@code http://host:port/api} with the supplied credentials. */
    public RabbitMqManagementClient(String host, int port, String user, String password) {
        this(apiBase(host, port), user, password, true);
    }

    /**
     * Lowest-level constructor: {@code apiBase} is the full API root (trailing slash optional),
     * e.g. {@code http://localhost:15672/api} or {@code https://broker/api}. The {@code fullBase}
     * flag disambiguates this constructor from {@link #RabbitMqManagementClient(String, String, String)}.
     */
    public RabbitMqManagementClient(String apiBase, String user, String password, boolean fullBase) {
        this.apiBase = stripTrailingSlash(apiBase);
        this.authHeader = basicAuthHeader(user, password);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    // ------------------------------------------------------------------
    // Pure helpers: URL/path building, vhost encoding, auth header.
    // ------------------------------------------------------------------

    /** Builds the API root for a plain-HTTP broker: {@code http://host:port/api}. */
    public static String apiBase(String host, int port) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host is required");
        }
        return "http://" + host + ":" + port + "/api";
    }

    /** Formats the {@code Authorization} header value for HTTP Basic auth. */
    public static String basicAuthHeader(String user, String password) {
        String creds = (user == null ? "" : user) + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Percent-encodes a path segment the way the management API expects. Notably the default vhost
     * {@code "/"} becomes {@code "%2F"}; spaces become {@code "%20"} (not {@code "+"}).
     */
    public static String encodeSegment(String segment) {
        String encoded = URLEncoder.encode(segment == null ? "" : segment, StandardCharsets.UTF_8);
        // URLEncoder is form-encoding; fix the differences the management API cares about.
        return encoded.replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    /** {@code GET /api/overview}. */
    public String overviewPath() {
        return apiBase + "/overview";
    }

    /** {@code GET /api/queues} (all vhosts) when {@code vhost} is {@code null}, else for one vhost. */
    public String queuesPath(String vhost) {
        return vhost == null ? apiBase + "/queues" : apiBase + "/queues/" + encodeSegment(vhost);
    }

    /** {@code GET /api/queues/{vhost}/{name}}. */
    public String queuePath(String vhost, String name) {
        return apiBase + "/queues/" + encodeSegment(vhost) + "/" + encodeSegment(name);
    }

    /** {@code DELETE /api/queues/{vhost}/{name}/contents}. */
    public String purgeQueuePath(String vhost, String name) {
        return queuePath(vhost, name) + "/contents";
    }

    /** {@code GET /api/exchanges} (all vhosts) or for a single vhost. */
    public String exchangesPath(String vhost) {
        return vhost == null ? apiBase + "/exchanges" : apiBase + "/exchanges/" + encodeSegment(vhost);
    }

    /** {@code GET /api/bindings} (all vhosts) or for a single vhost. */
    public String bindingsPath(String vhost) {
        return vhost == null ? apiBase + "/bindings" : apiBase + "/bindings/" + encodeSegment(vhost);
    }

    String apiBase() {
        return apiBase;
    }

    String authHeader() {
        return authHeader;
    }

    // ------------------------------------------------------------------
    // Pure helpers: JSON parsing into model records.
    // ------------------------------------------------------------------

    /** Parses the body of {@code GET /api/overview}. */
    public static OverviewInfo parseOverview(String json) {
        JsonNode root = readTree(json);
        JsonNode objs = root.path("object_totals");
        JsonNode msgs = root.path("queue_totals");
        return new OverviewInfo(
                text(root, "rabbitmq_version"),
                text(root, "erlang_version"),
                text(root, "cluster_name"),
                longOf(objs, "queues"),
                longOf(objs, "exchanges"),
                longOf(objs, "connections"),
                longOf(objs, "channels"),
                longOf(objs, "consumers"),
                longOf(msgs, "messages"),
                longOf(msgs, "messages_ready"),
                longOf(msgs, "messages_unacknowledged"));
    }

    /** Parses the JSON array body of {@code GET /api/queues}. */
    public static List<QueueInfo> parseQueues(String json) {
        List<QueueInfo> out = new ArrayList<>();
        for (JsonNode n : readTree(json)) {
            out.add(parseQueueNode(n));
        }
        return out;
    }

    /** Parses the single-object body of {@code GET /api/queues/{vhost}/{name}}. */
    public static QueueInfo parseQueue(String json) {
        return parseQueueNode(readTree(json));
    }

    /** Parses the JSON array body of {@code GET /api/exchanges}. */
    public static List<ExchangeInfo> parseExchanges(String json) {
        List<ExchangeInfo> out = new ArrayList<>();
        for (JsonNode n : readTree(json)) {
            out.add(new ExchangeInfo(
                    text(n, "name"),
                    text(n, "vhost"),
                    text(n, "type"),
                    bool(n, "durable"),
                    bool(n, "auto_delete"),
                    bool(n, "internal")));
        }
        return out;
    }

    /** Parses the JSON array body of {@code GET /api/bindings}. */
    public static List<BindingInfo> parseBindings(String json) {
        List<BindingInfo> out = new ArrayList<>();
        for (JsonNode n : readTree(json)) {
            out.add(new BindingInfo(
                    text(n, "source"),
                    text(n, "vhost"),
                    text(n, "destination"),
                    text(n, "destination_type"),
                    text(n, "routing_key")));
        }
        return out;
    }

    private static QueueInfo parseQueueNode(JsonNode n) {
        return new QueueInfo(
                text(n, "name"),
                text(n, "vhost"),
                text(n, "type"),
                bool(n, "durable"),
                bool(n, "auto_delete"),
                text(n, "state"),
                text(n, "node"),
                longOf(n, "messages"),
                longOf(n, "messages_ready"),
                longOf(n, "messages_unacknowledged"),
                longOf(n, "consumers"));
    }

    // ------------------------------------------------------------------
    // Network calls.
    // ------------------------------------------------------------------

    public OverviewInfo overview() {
        return parseOverview(get(overviewPath()));
    }

    public List<QueueInfo> listQueues() {
        return parseQueues(get(queuesPath(null)));
    }

    public List<QueueInfo> listQueues(String vhost) {
        return parseQueues(get(queuesPath(vhost)));
    }

    public List<ExchangeInfo> listExchanges() {
        return parseExchanges(get(exchangesPath(null)));
    }

    public List<BindingInfo> listBindings() {
        return parseBindings(get(bindingsPath(null)));
    }

    public QueueInfo getQueue(String vhost, String name) {
        return parseQueue(get(queuePath(vhost, name)));
    }

    /** Empties a queue via {@code DELETE .../contents}. */
    public void purgeQueue(String vhost, String name) {
        send(HttpRequest.newBuilder(URI.create(purgeQueuePath(vhost, name))).DELETE());
    }

    private String get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    private String send(HttpRequest.Builder builder) {
        try {
            HttpRequest req = builder
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", authHeader)
                    .header("Accept", "application/json")
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new RabbitMqManagementException(
                        "HTTP " + resp.statusCode() + " from " + req.uri() + ": " + truncate(resp.body()));
            }
            return resp.body();
        } catch (RabbitMqManagementException e) {
            throw e;
        } catch (Exception e) {
            throw new RabbitMqManagementException("Management API request failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Small internals.
    // ------------------------------------------------------------------

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RabbitMqManagementException("Invalid JSON from management API: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static long longOf(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? 0L : v.asLong();
    }

    private static boolean bool(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.asBoolean();
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("apiBase is required");
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
