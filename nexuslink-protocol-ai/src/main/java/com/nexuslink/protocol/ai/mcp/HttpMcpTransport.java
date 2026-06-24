package com.nexuslink.protocol.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static com.nexuslink.protocol.ai.mcp.JsonRpc.MAPPER;

/**
 * MCP Streamable HTTP transport: POSTs JSON-RPC to the server endpoint and reads the
 * JSON-RPC reply. Handles both a plain {@code application/json} body and a single
 * {@code text/event-stream} (SSE) response by extracting the first {@code data:} payload.
 * An {@code Mcp-Session-Id} header returned by the server is echoed on later requests.
 */
public final class HttpMcpTransport implements McpTransport {

    private final URI endpoint;
    private final Map<String, String> extraHeaders;
    private final HttpClient http;
    private volatile String sessionId;

    public HttpMcpTransport(String url, Map<String, String> extraHeaders) {
        this.endpoint = URI.create(url);
        this.extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public void open() {
        // Stateless until first request; nothing to pre-open for HTTP.
    }

    @Override
    public JsonNode sendRequest(ObjectNode request) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString(), StandardCharsets.UTF_8));
            extraHeaders.forEach(b::header);
            if (sessionId != null) b.header("Mcp-Session-Id", sessionId);

            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            resp.headers().firstValue("Mcp-Session-Id").ifPresent(s -> this.sessionId = s);

            if (resp.statusCode() / 100 != 2) {
                throw new McpException("HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
            }
            String body = resp.body();
            String contentType = resp.headers().firstValue("Content-Type").orElse("");
            String json = contentType.contains("text/event-stream") ? extractSseData(body) : body;
            return MAPPER.readTree(json);
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("HTTP transport error: " + e.getMessage(), e);
        }
    }

    @Override
    public void sendNotification(ObjectNode notification) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(notification.toString(), StandardCharsets.UTF_8));
            extraHeaders.forEach(b::header);
            if (sessionId != null) b.header("Mcp-Session-Id", sessionId);
            http.send(b.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new McpException("HTTP notification error: " + e.getMessage(), e);
        }
    }

    private String extractSseData(String body) {
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\n")) {
            if (line.startsWith("data:")) data.append(line.substring(5).trim());
        }
        return data.length() > 0 ? data.toString() : body;
    }

    private String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    @Override
    public void close() {
        // HttpClient has no explicit close before Java 21's AutoCloseable; nothing to release here.
    }
}
