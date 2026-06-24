package com.nexuslink.protocol.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Minimal JSON-RPC 2.0 message helpers used by the MCP client.
 * MCP (Model Context Protocol) is JSON-RPC 2.0 over a transport (Streamable HTTP or stdio).
 */
public final class JsonRpc {

    public static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String VERSION = "2.0";

    private JsonRpc() {}

    /** Build a JSON-RPC request with an id (expects a response). */
    public static ObjectNode request(long id, String method, JsonNode params) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", VERSION);
        node.put("id", id);
        node.put("method", method);
        if (params != null) node.set("params", params);
        else node.set("params", MAPPER.createObjectNode());
        return node;
    }

    /** Build a JSON-RPC notification (no id, no response expected). */
    public static ObjectNode notification(String method, JsonNode params) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", VERSION);
        node.put("method", method);
        if (params != null) node.set("params", params);
        return node;
    }

    /** Extracts the error message from a JSON-RPC error response, or null if none. */
    public static String errorMessage(JsonNode response) {
        JsonNode error = response.get("error");
        if (error == null || error.isNull()) return null;
        StringBuilder sb = new StringBuilder();
        if (error.has("code")) sb.append('[').append(error.get("code").asText()).append("] ");
        sb.append(error.path("message").asText("unknown error"));
        if (error.has("data")) sb.append(" — ").append(error.get("data").toString());
        return sb.toString();
    }

    public static boolean isError(JsonNode response) {
        return response != null && response.has("error") && !response.get("error").isNull();
    }
}
