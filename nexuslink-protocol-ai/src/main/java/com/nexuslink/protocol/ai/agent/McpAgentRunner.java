package com.nexuslink.protocol.ai.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexuslink.protocol.ai.llm.AnthropicService;
import com.nexuslink.protocol.ai.mcp.McpTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The "agent testing" endgame: hands an MCP server's tools to Claude and runs the full
 * tool-calling loop. Each turn the model may emit {@code tool_use} blocks; the runner executes them
 * via the supplied {@link ToolExecutor} (wired to {@code McpClient.callTool}), feeds the results
 * back as {@code tool_result} blocks, and repeats until the model answers without calling a tool
 * (or a turn cap is hit). Thinking and tool_use blocks are preserved verbatim between turns because
 * each assistant {@link Message} is appended whole.
 *
 * <p>The {@link #toAnthropicTool} conversion is pure (no network) and is the unit-tested seam; the
 * full {@link #run} needs a live {@code ANTHROPIC_API_KEY} and an MCP server, so the UI drives it on
 * a background thread.
 */
public final class McpAgentRunner {

    /** Executes one MCP tool call. Wire to {@code McpClient::callTool}. */
    public interface ToolExecutor {
        McpTypes.ToolResult call(String toolName, JsonNode arguments) throws Exception;
    }

    /** Streams agent steps for live rendering. All callbacks fire off the UI thread. */
    public interface Listener {
        default void onTurn(int turn) {}
        default void onAssistantText(String text) {}
        default void onToolCall(String toolName, JsonNode arguments) {}
        default void onToolResult(String toolName, String resultText, boolean isError) {}
    }

    /** Outcome of an agent run. */
    public record Result(
            boolean success, String finalText, int turns, int toolCalls,
            long inputTokens, long outputTokens, long durationMs, String error) {}

    private static final long MAX_TOKENS = 16_000L;
    private final int maxTurns;

    public McpAgentRunner() { this(12); }

    public McpAgentRunner(int maxTurns) { this.maxTurns = Math.max(1, maxTurns); }

    /**
     * Converts an MCP tool descriptor into an Anthropic {@link Tool} — pure, no network. The MCP
     * {@code inputSchema} (a JSON-Schema object) is mapped to the Anthropic input schema's
     * properties + required list, preserving each property's schema verbatim.
     */
    public static Tool toAnthropicTool(McpTypes.Tool t) {
        Tool.InputSchema.Builder schema = Tool.InputSchema.builder();
        JsonNode input = t.inputSchema();
        JsonNode props = input == null ? null : input.get("properties");
        if (props != null && props.isObject()) {
            Tool.InputSchema.Properties.Builder pb = Tool.InputSchema.Properties.builder();
            for (Map.Entry<String, JsonNode> e : iterable(props)) {
                pb.putAdditionalProperty(e.getKey(), JsonValue.fromJsonNode(e.getValue()));
            }
            schema.properties(pb.build());
        }
        JsonNode required = input == null ? null : input.get("required");
        if (required != null && required.isArray()) {
            List<String> names = new ArrayList<>();
            required.forEach(n -> names.add(n.asText()));
            schema.required(names);
        }
        Tool.Builder b = Tool.builder().name(t.name()).inputSchema(schema.build());
        if (t.description() != null && !t.description().isBlank()) b.description(t.description());
        return b.build();
    }

    /** Runs the tool-calling loop to completion (or the turn cap). Never call from the UI thread. */
    public Result run(String model, String systemPrompt, String userMessage,
                      List<McpTypes.Tool> tools, ToolExecutor executor, Listener listener) {
        long start = System.nanoTime();
        Listener l = listener == null ? new Listener() {} : listener;
        try {
            AnthropicClient client = AnthropicOkHttpClient.fromEnv();

            MessageCreateParams.Builder builder = MessageCreateParams.builder()
                    .model(model == null || model.isBlank() ? AnthropicService.DEFAULT_MODEL : model)
                    .maxTokens(MAX_TOKENS)
                    .addUserMessage(userMessage);
            if (systemPrompt != null && !systemPrompt.isBlank()) builder.system(systemPrompt);
            for (McpTypes.Tool t : tools) builder.addTool(ToolUnion.ofTool(toAnthropicTool(t)));

            long inputTokens = 0, outputTokens = 0;
            int toolCalls = 0;
            String finalText = "";

            for (int turn = 1; turn <= maxTurns; turn++) {
                l.onTurn(turn);
                Message response = client.messages().create(builder.build());
                inputTokens += response.usage().inputTokens();
                outputTokens += response.usage().outputTokens();

                StringBuilder text = new StringBuilder();
                List<ToolUseBlock> toolUses = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    block.text().ifPresent(tb -> text.append(tb.text()));
                    block.toolUse().ifPresent(toolUses::add);
                }
                if (!text.isEmpty()) {
                    finalText = text.toString();
                    l.onAssistantText(finalText);
                }

                // Append the assistant turn whole (keeps thinking + tool_use blocks intact).
                builder.addMessage(response);

                if (toolUses.isEmpty()) {
                    return new Result(true, finalText, turn, toolCalls,
                            inputTokens, outputTokens, elapsed(start), null);
                }

                List<ContentBlockParam> toolResults = new ArrayList<>();
                for (ToolUseBlock use : toolUses) {
                    toolCalls++;
                    JsonNode arguments = use._input().convert(JsonNode.class);
                    l.onToolCall(use.name(), arguments);

                    String resultText;
                    boolean isError;
                    try {
                        McpTypes.ToolResult tr = executor.call(use.name(), arguments);
                        resultText = flatten(tr.content());
                        isError = tr.isError();
                    } catch (Exception ex) {
                        resultText = "Tool execution failed: " + ex.getMessage();
                        isError = true;
                    }
                    l.onToolResult(use.name(), resultText, isError);
                    toolResults.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(use.id())
                            .content(resultText)
                            .isError(isError)
                            .build()));
                }
                builder.addUserMessageOfBlockParams(toolResults);
            }

            return new Result(false, finalText, maxTurns, toolCalls,
                    inputTokens, outputTokens, elapsed(start),
                    "Reached the " + maxTurns + "-turn limit without a final answer");
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + ": "
                    + (e.getMessage() == null ? "(no detail)" : e.getMessage());
            return new Result(false, "", 0, 0, 0, 0, elapsed(start), msg);
        }
    }

    private static String flatten(List<McpTypes.Content> content) {
        if (content == null || content.isEmpty()) return "(no content)";
        StringBuilder sb = new StringBuilder();
        for (McpTypes.Content c : content) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(c.text());
        }
        return sb.toString();
    }

    private static Iterable<Map.Entry<String, JsonNode>> iterable(JsonNode objectNode) {
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        objectNode.fields().forEachRemaining(entries::add);
        return entries;
    }

    private static long elapsed(long start) {
        return Math.round((System.nanoTime() - start) / 1_000_000.0);
    }
}
