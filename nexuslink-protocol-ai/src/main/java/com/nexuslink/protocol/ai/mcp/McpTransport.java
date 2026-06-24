package com.nexuslink.protocol.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pluggable MCP transport. Implementations carry JSON-RPC messages to/from an MCP server.
 * Two concrete transports ship: {@link HttpMcpTransport} (Streamable HTTP) and
 * {@link StdioMcpTransport} (subprocess over stdin/stdout). Tests inject a mock.
 */
public interface McpTransport extends AutoCloseable {

    /** Open the transport (start subprocess / prepare HTTP client). */
    void open() throws McpException;

    /** Send a JSON-RPC request and block for the matching response. */
    JsonNode sendRequest(ObjectNode request) throws McpException;

    /** Send a JSON-RPC notification (fire-and-forget; no response). */
    void sendNotification(ObjectNode notification) throws McpException;

    @Override
    void close();

    /** Unchecked transport/protocol error. */
    final class McpException extends RuntimeException {
        public McpException(String message) { super(message); }
        public McpException(String message, Throwable cause) { super(message, cause); }
    }
}
