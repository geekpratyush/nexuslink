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

    /**
     * Records the protocol version negotiated during {@code initialize}. Streamable HTTP
     * requires echoing it as the {@code MCP-Protocol-Version} header on every request after
     * initialization (MCP rev. 2025-03-26+); omitting it makes strict servers reply 400.
     * No-op for transports that don't need it (e.g. stdio).
     */
    default void setProtocolVersion(String version) {}

    @Override
    void close();

    /** Unchecked transport/protocol error. */
    final class McpException extends RuntimeException {
        public McpException(String message) { super(message); }
        public McpException(String message, Throwable cause) { super(message, cause); }
    }
}
