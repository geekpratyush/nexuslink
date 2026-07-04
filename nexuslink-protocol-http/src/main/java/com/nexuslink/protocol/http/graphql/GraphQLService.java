package com.nexuslink.protocol.http.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Minimal GraphQL-over-HTTP client: POSTs {@code {"query":…, "variables":…}} as JSON and returns
 * the (pretty-printed) response. Works against any standard GraphQL endpoint.
 */
public final class GraphQLService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    /** Result of a GraphQL call: HTTP status, pretty body, elapsed ms, and any transport error. */
    public record Result(int status, String body, long durationMs, String error) {
        public boolean failed() { return error != null; }
    }

    /**
     * Introspection query listing the schema's root operations and every type with its fields (each
     * field's args and unwrapped type), enough to drive {@link GraphQLSchema} and schema-aware
     * completion. The nested {@code ofType} chain unwraps NON_NULL/LIST wrappers to the named type.
     */
    public static final String INTROSPECTION_QUERY =
            "{ __schema { queryType { name } mutationType { name } subscriptionType { name } "
          + "types { name kind fields { name args { name } "
          + "type { name kind ofType { name kind ofType { name kind ofType { name } } } } } } } }";

    public Result execute(String endpoint, String query, String variablesJson, Map<String, String> headers) {
        long start = System.nanoTime();
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("query", query);
            if (variablesJson != null && !variablesJson.isBlank()) {
                payload.set("variables", MAPPER.readTree(variablesJson));
            }

            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "NexusLink/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
            if (headers != null) headers.forEach((k, v) -> { if (!k.isBlank()) b.header(k, v); });

            HttpResponse<String> resp = client.send(b.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Result(resp.statusCode(), pretty(resp.body()), ms(start), null);
        } catch (Exception e) {
            return new Result(0, "", ms(start), e.getMessage());
        }
    }

    private String pretty(String body) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(MAPPER.readTree(body));
        } catch (Exception e) {
            return body; // not JSON — return as-is
        }
    }

    private long ms(long startNanos) {
        return Math.round((System.nanoTime() - startNanos) / 1_000_000.0);
    }
}
