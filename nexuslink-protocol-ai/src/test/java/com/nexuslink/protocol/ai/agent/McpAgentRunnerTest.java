package com.nexuslink.protocol.ai.agent;

import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexuslink.protocol.ai.mcp.McpTypes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the pure {@link McpAgentRunner#toAnthropicTool} seam — MCP tool descriptors map to
 * Anthropic {@link Tool} objects with the right name/description and a faithful input schema — so
 * the agent wiring is tested without a live API key or MCP server.
 */
class McpAgentRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode schema(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void mapsNameDescriptionAndSchemaProperties() throws Exception {
        McpTypes.Tool mcp = new McpTypes.Tool("get_weather", "Look up the weather",
                schema("""
                    { "type": "object",
                      "properties": {
                        "city": { "type": "string", "description": "City name" },
                        "units": { "type": "string", "enum": ["c", "f"] }
                      },
                      "required": ["city"] }
                    """));

        Tool tool = McpAgentRunner.toAnthropicTool(mcp);

        assertEquals("get_weather", tool.name());
        assertEquals("Look up the weather", tool.description().orElseThrow());

        Tool.InputSchema input = tool.inputSchema();
        assertTrue(input.properties().isPresent());
        assertTrue(input.properties().get()._additionalProperties().containsKey("city"));
        assertTrue(input.properties().get()._additionalProperties().containsKey("units"));
        assertEquals(List.of("city"), input.required().orElseThrow());
    }

    @Test
    void toolWithoutDescriptionOrSchemaStillConverts() throws Exception {
        McpTypes.Tool mcp = new McpTypes.Tool("ping", "", schema("{ \"type\": \"object\" }"));

        Tool tool = McpAgentRunner.toAnthropicTool(mcp);

        assertEquals("ping", tool.name());
        assertFalse(tool.description().isPresent());
        // No properties block declared — required stays empty.
        assertTrue(input(tool).required().isEmpty() || input(tool).required().get().isEmpty());
    }

    @Test
    void preservesNestedPropertySchema() throws Exception {
        McpTypes.Tool mcp = new McpTypes.Tool("search", "Search docs",
                schema("""
                    { "type": "object",
                      "properties": {
                        "filters": { "type": "object",
                                     "properties": { "lang": { "type": "string" } } }
                      } }
                    """));

        Tool tool = McpAgentRunner.toAnthropicTool(mcp);
        Object filters = input(tool).properties().get()._additionalProperties().get("filters");
        // The nested schema round-trips through JsonValue, so its JSON still mentions the inner field.
        assertTrue(filters.toString().contains("lang"));
    }

    private static Tool.InputSchema input(Tool tool) {
        return tool.inputSchema();
    }
}
