package com.nexuslink.protocol.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.nexuslink.protocol.ai.mcp.JsonRpc.MAPPER;

/**
 * High-level Model Context Protocol client. Drives the JSON-RPC handshake
 * ({@code initialize} → {@code notifications/initialized}) over a {@link McpTransport},
 * then exposes the standard discovery/invocation methods.
 *
 * <p>Protocol reference: tools/list, tools/call, resources/list, resources/read,
 * prompts/list, prompts/get — all JSON-RPC 2.0 methods defined by MCP.
 */
public final class McpClient implements AutoCloseable {

    /** Protocol revision NexusLink advertises during initialize. */
    public static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpTransport transport;
    private final AtomicLong nextId = new AtomicLong(1);
    private McpTypes.ServerInfo serverInfo;

    public McpClient(McpTransport transport) {
        this.transport = transport;
    }

    /** Opens the transport and performs the MCP initialize handshake. */
    public McpTypes.ServerInfo connect() {
        transport.open();

        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", MAPPER.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "NexusLink");
        clientInfo.put("version", "1.0.0");

        JsonNode result = call("initialize", params);
        JsonNode info = result.path("serverInfo");
        this.serverInfo = new McpTypes.ServerInfo(
                info.path("name").asText("(unknown)"),
                info.path("version").asText(""),
                result.path("protocolVersion").asText(PROTOCOL_VERSION),
                result.path("capabilities"));

        // Streamable HTTP requires the negotiated version on all later requests; hand it to the
        // transport before the initialized notification (which is itself a subsequent request).
        transport.setProtocolVersion(serverInfo.protocolVersion());

        // Per spec, follow up with the initialized notification.
        transport.sendNotification(JsonRpc.notification("notifications/initialized", MAPPER.createObjectNode()));
        return serverInfo;
    }

    public McpTypes.ServerInfo serverInfo() {
        return serverInfo;
    }

    /**
     * Whether the server advertised a top-level capability (e.g. {@code "tools"},
     * {@code "resources"}, {@code "prompts"}) during initialize. Calling a method for an
     * unadvertised capability is what produces "prompts not supported"-style errors, so
     * callers should gate discovery on this.
     */
    public boolean serverSupports(String capability) {
        return serverInfo != null && serverInfo.capabilities().has(capability);
    }

    // ---- Tools ----

    public List<McpTypes.Tool> listTools() {
        JsonNode result = call("tools/list", MAPPER.createObjectNode());
        List<McpTypes.Tool> tools = new ArrayList<>();
        for (JsonNode t : result.path("tools")) {
            tools.add(new McpTypes.Tool(
                    t.path("name").asText(),
                    t.path("description").asText(""),
                    t.path("inputSchema")));
        }
        return tools;
    }

    public McpTypes.ToolResult callTool(String name, JsonNode arguments) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? MAPPER.createObjectNode() : arguments);
        JsonNode result = call("tools/call", params);
        return new McpTypes.ToolResult(
                parseContent(result.path("content")),
                result.path("isError").asBoolean(false));
    }

    // ---- Resources ----

    public List<McpTypes.Resource> listResources() {
        JsonNode result = call("resources/list", MAPPER.createObjectNode());
        List<McpTypes.Resource> resources = new ArrayList<>();
        for (JsonNode r : result.path("resources")) {
            resources.add(new McpTypes.Resource(
                    r.path("uri").asText(),
                    r.path("name").asText(""),
                    r.path("description").asText(""),
                    r.path("mimeType").asText("")));
        }
        return resources;
    }

    public List<McpTypes.Content> readResource(String uri) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("uri", uri);
        JsonNode result = call("resources/read", params);
        List<McpTypes.Content> out = new ArrayList<>();
        for (JsonNode c : result.path("contents")) {
            String text = c.has("text") ? c.path("text").asText()
                    : c.has("blob") ? "(binary blob, " + c.path("blob").asText().length() + " b64 chars)"
                    : "";
            out.add(new McpTypes.Content(c.path("mimeType").asText("text"), text));
        }
        return out;
    }

    // ---- Prompts ----

    public List<McpTypes.Prompt> listPrompts() {
        JsonNode result = call("prompts/list", MAPPER.createObjectNode());
        List<McpTypes.Prompt> prompts = new ArrayList<>();
        for (JsonNode p : result.path("prompts")) {
            List<McpTypes.PromptArgument> args = new ArrayList<>();
            for (JsonNode a : p.path("arguments")) {
                args.add(new McpTypes.PromptArgument(
                        a.path("name").asText(),
                        a.path("description").asText(""),
                        a.path("required").asBoolean(false)));
            }
            prompts.add(new McpTypes.Prompt(
                    p.path("name").asText(),
                    p.path("description").asText(""),
                    args));
        }
        return prompts;
    }

    public List<McpTypes.Content> getPrompt(String name, JsonNode arguments) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? MAPPER.createObjectNode() : arguments);
        JsonNode result = call("prompts/get", params);
        List<McpTypes.Content> out = new ArrayList<>();
        for (JsonNode m : result.path("messages")) {
            JsonNode content = m.path("content");
            out.add(new McpTypes.Content(
                    m.path("role").asText("") + "/" + content.path("type").asText("text"),
                    content.path("text").asText("")));
        }
        return out;
    }

    // ---- internals ----

    /** Issues a request and unwraps the JSON-RPC {@code result}, raising on error. */
    private JsonNode call(String method, JsonNode params) {
        ObjectNode request = JsonRpc.request(nextId.getAndIncrement(), method, params);
        JsonNode response = transport.sendRequest(request);
        if (JsonRpc.isError(response)) {
            throw new McpTransport.McpException(method + " failed: " + JsonRpc.errorMessage(response));
        }
        return response.path("result");
    }

    private List<McpTypes.Content> parseContent(JsonNode contentArray) {
        List<McpTypes.Content> out = new ArrayList<>();
        if (contentArray instanceof ArrayNode arr) {
            for (JsonNode c : arr) {
                String type = c.path("type").asText("text");
                String text = c.has("text") ? c.path("text").asText() : c.toString();
                out.add(new McpTypes.Content(type, text));
            }
        }
        return out;
    }

    @Override
    public void close() {
        transport.close();
    }
}
