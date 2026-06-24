package com.nexuslink.protocol.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Lightweight value objects for the entities an MCP server exposes.
 * Raw JSON is preserved where useful (e.g. tool input schema) for display.
 */
public final class McpTypes {

    private McpTypes() {}

    /** Result of the initialize handshake. */
    public record ServerInfo(String name, String version, String protocolVersion, JsonNode capabilities) {}

    /** A callable tool the server exposes (tools/list). */
    public record Tool(String name, String description, JsonNode inputSchema) {}

    /** A readable resource (resources/list). */
    public record Resource(String uri, String name, String description, String mimeType) {}

    /** A prompt template (prompts/list). */
    public record Prompt(String name, String description, List<PromptArgument> arguments) {}

    public record PromptArgument(String name, String description, boolean required) {}

    /** Result of a tools/call — content blocks plus an error flag. */
    public record ToolResult(List<Content> content, boolean isError) {}

    /** A content block (text or other) returned by tools/resources/prompts. */
    public record Content(String type, String text) {}
}
