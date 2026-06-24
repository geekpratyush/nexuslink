package com.nexuslink.protocol.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.nexuslink.protocol.ai.mcp.JsonRpc.MAPPER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the MCP client's handshake and discovery/invocation logic against an
 * in-memory transport that mimics a spec-compliant MCP server's JSON-RPC replies.
 */
class McpClientTest {

    /** A fake MCP server: routes JSON-RPC methods to canned responses. */
    static final class MockServer implements McpTransport {
        final List<String> methodsSeen = new ArrayList<>();
        boolean initializedNotified = false;

        @Override public void open() {}
        @Override public void close() {}

        @Override
        public JsonNode sendRequest(ObjectNode request) {
            String method = request.path("method").asText();
            methodsSeen.add(method);
            long id = request.path("id").asLong();
            ObjectNode resp = MAPPER.createObjectNode();
            resp.put("jsonrpc", "2.0");
            resp.put("id", id);
            resp.set("result", resultFor(method, request.path("params")));
            return resp;
        }

        @Override
        public void sendNotification(ObjectNode notification) {
            if ("notifications/initialized".equals(notification.path("method").asText())) {
                initializedNotified = true;
            }
        }

        private JsonNode resultFor(String method, JsonNode params) {
            return switch (method) {
                case "initialize" -> {
                    ObjectNode r = MAPPER.createObjectNode();
                    r.put("protocolVersion", "2024-11-05");
                    r.set("capabilities", MAPPER.createObjectNode());
                    ObjectNode info = r.putObject("serverInfo");
                    info.put("name", "everything-server");
                    info.put("version", "0.9.1");
                    yield r;
                }
                case "tools/list" -> {
                    ObjectNode r = MAPPER.createObjectNode();
                    var tools = r.putArray("tools");
                    ObjectNode echo = tools.addObject();
                    echo.put("name", "echo");
                    echo.put("description", "Echoes back the input text");
                    ObjectNode schema = echo.putObject("inputSchema");
                    schema.put("type", "object");
                    schema.putObject("properties").putObject("text").put("type", "string");
                    yield r;
                }
                case "tools/call" -> {
                    String text = params.path("arguments").path("text").asText("");
                    ObjectNode r = MAPPER.createObjectNode();
                    r.put("isError", false);
                    ObjectNode block = r.putArray("content").addObject();
                    block.put("type", "text");
                    block.put("text", "Echo: " + text);
                    yield r;
                }
                case "resources/list" -> {
                    ObjectNode r = MAPPER.createObjectNode();
                    ObjectNode res = r.putArray("resources").addObject();
                    res.put("uri", "file:///readme.md");
                    res.put("name", "README");
                    res.put("mimeType", "text/markdown");
                    yield r;
                }
                case "resources/read" -> {
                    ObjectNode r = MAPPER.createObjectNode();
                    ObjectNode c = r.putArray("contents").addObject();
                    c.put("uri", params.path("uri").asText());
                    c.put("mimeType", "text/markdown");
                    c.put("text", "# Hello from MCP");
                    yield r;
                }
                case "prompts/list" -> {
                    ObjectNode r = MAPPER.createObjectNode();
                    ObjectNode p = r.putArray("prompts").addObject();
                    p.put("name", "summarize");
                    p.put("description", "Summarize a document");
                    ObjectNode arg = p.putArray("arguments").addObject();
                    arg.put("name", "doc");
                    arg.put("required", true);
                    yield r;
                }
                case "prompts/get" -> {
                    ObjectNode r = MAPPER.createObjectNode();
                    ObjectNode msg = r.putArray("messages").addObject();
                    msg.put("role", "user");
                    ObjectNode content = msg.putObject("content");
                    content.put("type", "text");
                    content.put("text", "Summarize: " + params.path("arguments").path("doc").asText());
                    yield r;
                }
                default -> MAPPER.createObjectNode();
            };
        }
    }

    @Test
    void handshakeReturnsServerInfoAndSendsInitializedNotification() {
        MockServer server = new MockServer();
        try (McpClient client = new McpClient(server)) {
            McpTypes.ServerInfo info = client.connect();
            assertEquals("everything-server", info.name());
            assertEquals("0.9.1", info.version());
            assertEquals("initialize", server.methodsSeen.get(0));
            assertTrue(server.initializedNotified, "client must send notifications/initialized");
        }
    }

    @Test
    void listsAndCallsTools() {
        MockServer server = new MockServer();
        try (McpClient client = new McpClient(server)) {
            client.connect();
            List<McpTypes.Tool> tools = client.listTools();
            assertEquals(1, tools.size());
            assertEquals("echo", tools.get(0).name());

            ObjectNode args = MAPPER.createObjectNode();
            args.put("text", "hello world");
            McpTypes.ToolResult result = client.callTool("echo", args);
            assertFalse(result.isError());
            assertEquals("Echo: hello world", result.content().get(0).text());
        }
    }

    @Test
    void listsAndReadsResources() {
        MockServer server = new MockServer();
        try (McpClient client = new McpClient(server)) {
            client.connect();
            List<McpTypes.Resource> resources = client.listResources();
            assertEquals("file:///readme.md", resources.get(0).uri());
            List<McpTypes.Content> contents = client.readResource("file:///readme.md");
            assertEquals("# Hello from MCP", contents.get(0).text());
        }
    }

    @Test
    void listsAndGetsPrompts() {
        MockServer server = new MockServer();
        try (McpClient client = new McpClient(server)) {
            client.connect();
            List<McpTypes.Prompt> prompts = client.listPrompts();
            assertEquals("summarize", prompts.get(0).name());
            assertTrue(prompts.get(0).arguments().get(0).required());

            ObjectNode args = MAPPER.createObjectNode();
            args.put("doc", "the report");
            List<McpTypes.Content> messages = client.getPrompt("summarize", args);
            assertEquals("Summarize: the report", messages.get(0).text());
        }
    }

    @Test
    void surfacesJsonRpcErrors() {
        McpTransport erroring = new McpTransport() {
            public void open() {}
            public void close() {}
            public void sendNotification(ObjectNode n) {}
            public JsonNode sendRequest(ObjectNode request) {
                ObjectNode resp = MAPPER.createObjectNode();
                resp.put("jsonrpc", "2.0");
                resp.put("id", request.path("id").asLong());
                ObjectNode err = resp.putObject("error");
                err.put("code", -32601);
                err.put("message", "Method not found");
                return resp;
            }
        };
        try (McpClient client = new McpClient(erroring)) {
            McpTransport.McpException ex = assertThrows(McpTransport.McpException.class, client::connect);
            assertTrue(ex.getMessage().contains("Method not found"));
        }
    }
}
