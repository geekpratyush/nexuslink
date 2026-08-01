package com.nexuslink.protocol.http.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Client-side message framing for the <b>graphql-transport-ws</b> subscription protocol
 * (the successor to the legacy {@code graphql-ws} / Apollo protocol). Pure, dependency-light
 * (Jackson only) so the framing can be unit-tested without a live socket or JavaFX.
 *
 * <p>Handshake: the client sends {@link #connectionInit()}; the server replies
 * {@code connection_ack}; the client then {@link #subscribe subscribe}s with an operation id;
 * the server streams {@code next} payloads and ends with {@code complete} (or {@code error});
 * the client may stop early with {@link #complete(String)}. Servers may also send {@code ping},
 * which the client answers with {@link #pong()}.
 */
public final class GraphQLWsProtocol {

    /** The WebSocket sub-protocol to negotiate (via {@code Sec-WebSocket-Protocol}). */
    public static final String SUBPROTOCOL = "graphql-transport-ws";

    // Server message types.
    public static final String CONNECTION_ACK = "connection_ack";
    public static final String NEXT = "next";
    public static final String ERROR = "error";
    public static final String COMPLETE = "complete";
    public static final String PING = "ping";
    public static final String PONG = "pong";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GraphQLWsProtocol() {}

    /** First client frame: {@code {"type":"connection_init"}}. */
    public static String connectionInit() {
        return "{\"type\":\"connection_init\"}";
    }

    /**
     * Starts an operation: {@code {"id":…,"type":"subscribe","payload":{"query":…,"variables":…}}}.
     * {@code variablesJson}, if non-blank, must be a JSON object.
     */
    public static String subscribe(String id, String query, String variablesJson) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("id", id);
            root.put("type", "subscribe");
            ObjectNode payload = root.putObject("payload");
            payload.put("query", query == null ? "" : query);
            if (variablesJson != null && !variablesJson.isBlank()) {
                payload.set("variables", MAPPER.readTree(variablesJson));
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid subscription variables: " + e.getMessage(), e);
        }
    }

    /** Stops an operation the client started: {@code {"id":…,"type":"complete"}}. */
    public static String complete(String id) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("id", id);
            root.put("type", "complete");
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reply to a server {@code ping}: {@code {"type":"pong"}}. */
    public static String pong() {
        return "{\"type\":\"pong\"}";
    }

    /**
     * A parsed inbound server message: its {@code type}, the operation {@code id} (may be {@code null}
     * for connection-level frames), and the raw {@code payload} JSON (may be {@code null}).
     */
    public record ServerMessage(String type, String id, String payload) {}

    /** Parses a raw inbound frame into a {@link ServerMessage}. Never throws on malformed input. */
    public static ServerMessage parse(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            String type = node.path("type").asText(null);
            String id = node.hasNonNull("id") ? node.get("id").asText() : null;
            String payload = node.has("payload") ? MAPPER.writeValueAsString(node.get("payload")) : null;
            return new ServerMessage(type, id, payload);
        } catch (Exception e) {
            return new ServerMessage(null, null, json);
        }
    }
}
