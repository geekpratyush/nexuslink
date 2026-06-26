package com.nexuslink.ui.agent;

import com.nexuslink.protocol.ai.agent.McpAgentRunner;
import com.nexuslink.protocol.ai.llm.AnthropicService;
import com.nexuslink.protocol.ai.mcp.HttpMcpTransport;
import com.nexuslink.protocol.ai.mcp.McpClient;
import com.nexuslink.protocol.ai.mcp.McpTransport;
import com.nexuslink.protocol.ai.mcp.McpTypes;
import com.nexuslink.protocol.ai.mcp.StdioMcpTransport;
import com.nexuslink.ui.env.Env;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AI Agent tab — the "agent testing" endgame. Connects to an MCP server, hands its tools to Claude,
 * and runs the full tool-calling loop ({@link McpAgentRunner}): the model plans, calls MCP tools,
 * sees their results, and continues until it answers. Each step (assistant text, tool call, tool
 * result) streams into a live transcript so you can watch the agent reason and act.
 */
public final class AgentView extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AnthropicService anthropic = new AnthropicService();
    private final McpAgentRunner runner = new McpAgentRunner();

    private final ComboBox<String> transportCombo = new ComboBox<>();
    private final TextField targetField = new TextField();
    private final PasswordField tokenField = new PasswordField();
    private final ComboBox<String> modelCombo = new ComboBox<>();
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    private final TextArea systemPrompt = new TextArea();
    private final TextArea taskInput = new TextArea();
    private final TextArea transcript = new TextArea();
    private final Button runBtn = new Button("Run agent");
    private final Label toolsLabel = new Label("No tools loaded");

    private volatile McpClient client;
    private volatile List<McpTypes.Tool> tools = List.of();
    private Consumer<String> logger = s -> {};

    public AgentView() {
        getStyleClass().add("agent-view");
        setTop(buildBar());
        setCenter(buildBody());
        refreshKeyStatus();
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    /** Pre-fills the MCP server target (and transport when it matches a known option). */
    public void prefill(String target, String transport) {
        if (target != null && !target.isBlank()) targetField.setText(target);
        if (transport != null) {
            for (String item : transportCombo.getItems()) {
                if (item.toLowerCase().contains(transport.toLowerCase())) { transportCombo.setValue(item); break; }
            }
        }
    }

    private VBox buildBar() {
        transportCombo.getItems().addAll("HTTP (Streamable)", "stdio (subprocess)");
        transportCombo.setValue("stdio (subprocess)");
        transportCombo.valueProperty().addListener((o, ov, nv) -> updatePrompt());

        targetField.getStyleClass().add("nl-field");
        HBox.setHgrow(targetField, Priority.ALWAYS);
        tokenField.getStyleClass().add("nl-field");
        tokenField.setPromptText("Bearer token (HTTP only)");
        tokenField.setPrefWidth(180);
        updatePrompt();

        modelCombo.getItems().addAll(AnthropicService.MODELS);
        modelCombo.setValue(AnthropicService.DEFAULT_MODEL);
        modelCombo.setPrefWidth(160);

        connectBtn.getStyleClass().add("btn-secondary");
        connectBtn.setOnAction(e -> connect());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("agent"));

        HBox row = new HBox(8, label("MCP:"), transportCombo, targetField, tokenField, connectBtn, helpBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 10, 4, 10));
        HBox row2 = new HBox(8, label("Model:"), modelCombo, toolsLabel);
        row2.setAlignment(Pos.CENTER_LEFT);
        row2.setPadding(new Insets(0, 10, 4, 10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row, row2, statusRow);
    }

    private SplitPane buildBody() {
        systemPrompt.getStyleClass().add("code-area");
        systemPrompt.setPromptText("System prompt (optional) — e.g. \"You are a careful ops assistant. Use the tools.\"");
        systemPrompt.setPrefRowCount(2);

        taskInput.getStyleClass().add("code-area");
        taskInput.setPromptText("Task for the agent — what should it accomplish using the MCP tools?");
        taskInput.setText("List the available tools and use them to answer: what can you do for me?");

        runBtn.getStyleClass().add("btn-primary");
        runBtn.setOnAction(e -> run());
        HBox runRow = new HBox(8, runBtn);
        runRow.setAlignment(Pos.CENTER_LEFT);

        VBox input = new VBox(6, label("System"), systemPrompt, label("Task"), taskInput, runRow);
        input.setPadding(new Insets(8));
        VBox.setVgrow(taskInput, Priority.ALWAYS);

        transcript.getStyleClass().add("code-area");
        transcript.setEditable(false);
        transcript.setPromptText("The agent transcript — turns, tool calls, results, and the final answer — appears here…");
        VBox output = new VBox(6, label("Transcript"), transcript);
        output.setPadding(new Insets(8));
        VBox.setVgrow(transcript, Priority.ALWAYS);

        SplitPane sp = new SplitPane(input, output);
        sp.setOrientation(javafx.geometry.Orientation.VERTICAL);
        sp.setDividerPositions(0.4);
        return sp;
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("meta-label");
        return l;
    }

    private void updatePrompt() {
        boolean http = transportCombo.getValue().startsWith("HTTP");
        targetField.setPromptText(http
                ? "https://mcp-server.example.com/mcp"
                : "command to launch the server, e.g. npx -y @modelcontextprotocol/server-everything");
        tokenField.setDisable(!http);
    }

    private void refreshKeyStatus() {
        if (anthropic.isConfigured()) {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("● ANTHROPIC_API_KEY detected — connect an MCP server to start");
        } else {
            statusLabel.getStyleClass().setAll("status-4xx");
            statusLabel.setText("● ANTHROPIC_API_KEY not set — export it and reopen this tab to run the agent");
        }
    }

    private void connect() {
        String target = Env.resolve(targetField.getText().trim());   // resolve ${VAR} against active environment
        if (target.isEmpty()) { statusLabel.setText("Enter an MCP server target first"); return; }
        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting to MCP server…");
        boolean http = transportCombo.getValue().startsWith("HTTP");
        Map<String, String> headers = http ? authHeaders() : Map.of();
        logger.accept("Agent MCP connect → " + target);

        Task<McpTypes.ServerInfo> task = new Task<>() {
            @Override protected McpTypes.ServerInfo call() {
                closeClient();
                McpTransport transport = http
                        ? new HttpMcpTransport(target, headers)
                        : new StdioMcpTransport(List.of(target.split("\\s+")));
                McpClient c = new McpClient(transport);
                McpTypes.ServerInfo info = c.connect();
                client = c;
                tools = c.serverSupports("tools") ? c.listTools() : List.of();
                return info;
            }
        };
        task.setOnSucceeded(e -> {
            McpTypes.ServerInfo info = task.getValue();
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + info.name() + " v" + info.version());
            toolsLabel.setText(tools.size() + " tool" + (tools.size() == 1 ? "" : "s") + " available");
            logger.accept("Agent MCP connected: " + info.name() + " (" + tools.size() + " tools)");
            connectBtn.setDisable(false);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            logger.accept("Agent MCP connect FAILED: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        runBg(task, "agent-mcp-connect");
    }

    private Map<String, String> authHeaders() {
        String token = tokenField.getText() == null ? "" : Env.resolve(tokenField.getText().trim());
        if (token.isEmpty()) return Map.of();
        boolean hasScheme = token.matches("(?i)^(bearer|basic|token)\\s.+");
        return Map.of("Authorization", hasScheme ? token : "Bearer " + token);
    }

    private void run() {
        if (!anthropic.isConfigured()) {
            append("✖ ANTHROPIC_API_KEY is not set — export it and reopen this tab.");
            return;
        }
        if (client == null) { append("✖ Connect an MCP server first."); return; }
        String task = Env.resolve(taskInput.getText().trim());   // resolve ${VAR} in the task
        if (task.isEmpty()) { append("✖ Enter a task for the agent."); return; }

        runBtn.setDisable(true);
        transcript.clear();
        String model = modelCombo.getValue();
        String system = Env.resolve(systemPrompt.getText());
        List<McpTypes.Tool> toolset = tools;
        McpClient c = client;
        append("▶ Running agent with " + toolset.size() + " tool(s) on " + model + "…");
        logger.accept("Agent run — " + toolset.size() + " tools, model " + model);

        McpAgentRunner.Listener listener = new McpAgentRunner.Listener() {
            @Override public void onTurn(int turn) {
                Platform.runLater(() -> append("— turn " + turn + " —"));
            }
            @Override public void onAssistantText(String text) {
                Platform.runLater(() -> append("🤖 " + text));
            }
            @Override public void onToolCall(String toolName, com.fasterxml.jackson.databind.JsonNode arguments) {
                Platform.runLater(() -> append("🔧 call " + toolName + " " + arguments));
            }
            @Override public void onToolResult(String toolName, String resultText, boolean isError) {
                Platform.runLater(() -> append((isError ? "⚠ " : "↳ ") + toolName + " → " + truncate(resultText)));
            }
        };

        Task<McpAgentRunner.Result> job = new Task<>() {
            @Override protected McpAgentRunner.Result call() {
                return runner.run(model, system, task, toolset, c::callTool, listener);
            }
        };
        job.setOnSucceeded(e -> { renderResult(job.getValue()); runBtn.setDisable(false); });
        job.setOnFailed(e -> {
            append("✖ Agent error: " + job.getException());
            runBtn.setDisable(false);
        });
        runBg(job, "agent-run");
    }

    private void renderResult(McpAgentRunner.Result r) {
        if (!r.success()) {
            append("✖ " + r.error());
            logger.accept("Agent FAILED — " + r.error());
            return;
        }
        append("✓ done — " + r.turns() + " turn(s), " + r.toolCalls() + " tool call(s), "
                + r.inputTokens() + " in / " + r.outputTokens() + " out tok, " + r.durationMs() + " ms");
        logger.accept("Agent ok — " + r.toolCalls() + " tool calls, " + r.durationMs() + "ms");
    }

    private void append(String line) {
        transcript.appendText(LocalTime.now().format(TIME) + "  " + line + "\n\n");
    }

    private String truncate(String s) {
        if (s == null) return "";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 240 ? oneLine.substring(0, 240) + "…" : oneLine;
    }

    private void closeClient() {
        McpClient c = client;
        client = null;
        if (c != null) try { c.close(); } catch (Exception ignored) { /* best-effort */ }
    }

    private void runBg(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }
}
