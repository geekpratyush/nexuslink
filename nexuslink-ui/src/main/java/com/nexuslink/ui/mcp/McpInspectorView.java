package com.nexuslink.ui.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexuslink.protocol.ai.mcp.*;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;
import java.util.function.Consumer;

import static com.nexuslink.protocol.ai.mcp.JsonRpc.MAPPER;

/**
 * MCP Inspector tab — connect to a Model Context Protocol server (HTTP or stdio),
 * then browse and exercise its tools, resources, and prompts.
 */
public final class McpInspectorView extends BorderPane {

    private final ComboBox<String> transportCombo = new ComboBox<>();
    private final TextField targetField = new TextField();
    private final PasswordField tokenField = new PasswordField();
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    private final ListView<McpTypes.Tool> toolList = new ListView<>();
    private final TextArea toolArgs = new TextArea();
    private final TextArea toolResult = new TextArea();

    private final ListView<McpTypes.Resource> resourceList = new ListView<>();
    private final TextArea resourceContent = new TextArea();

    private final ListView<McpTypes.Prompt> promptList = new ListView<>();
    private final TextArea promptArgs = new TextArea();
    private final TextArea promptResult = new TextArea();

    private McpClient client;
    private Consumer<String> logger = s -> {};

    public McpInspectorView() {
        getStyleClass().add("mcp-view");
        setTop(buildConnectionBar());
        setCenter(buildTabs());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    /** Pre-fills the server target (and transport if it matches a known option). */
    public void prefill(String target, String transport) {
        if (target != null && !target.isBlank()) targetField.setText(target);
        if (transport != null) {
            for (String item : transportCombo.getItems()) {
                if (item.toLowerCase().contains(transport.toLowerCase())) {
                    transportCombo.setValue(item);
                    break;
                }
            }
        }
    }

    // ---- connection ----

    private VBox buildConnectionBar() {
        transportCombo.getItems().addAll("HTTP (Streamable)", "stdio (subprocess)");
        transportCombo.setValue("stdio (subprocess)");
        transportCombo.valueProperty().addListener((o, ov, nv) -> updatePrompt());

        targetField.getStyleClass().add("nl-field");
        HBox.setHgrow(targetField, Priority.ALWAYS);

        tokenField.getStyleClass().add("nl-field");
        tokenField.setPromptText("Bearer token (optional)");
        tokenField.setPrefColumnCount(22);
        updatePrompt();

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("plugins"));

        HBox row = new HBox(8, transportCombo, targetField, tokenField, connectBtn, helpBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));

        return new VBox(row, statusRow);
    }

    private void updatePrompt() {
        boolean http = transportCombo.getValue().startsWith("HTTP");
        targetField.setPromptText(http
                ? "https://mcp.example.com/mcp"
                : "npx -y @modelcontextprotocol/server-everything");
        // A Bearer token only applies to the HTTP transport (stdio servers authenticate
        // however the subprocess is configured), so hide it for stdio to avoid confusion.
        tokenField.setManaged(http);
        tokenField.setVisible(http);
    }

    private void connect() {
        String target = targetField.getText().trim();
        if (target.isEmpty()) { statusLabel.setText("Enter a server target first"); return; }

        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label", "status-connecting");
        statusLabel.setText("Connecting…");
        boolean http = transportCombo.getValue().startsWith("HTTP");
        java.util.Map<String, String> headers = http ? authHeaders() : java.util.Map.of();
        logger.accept("MCP connect → " + target
                + (headers.isEmpty() ? "" : " (with Authorization)"));

        Task<McpTypes.ServerInfo> task = new Task<>() {
            @Override protected McpTypes.ServerInfo call() {
                McpTransport transport = http
                        ? new HttpMcpTransport(target, headers)
                        : new StdioMcpTransport(List.of(target.split("\\s+")));
                client = new McpClient(transport);
                return client.connect();
            }
        };
        task.setOnSucceeded(e -> {
            McpTypes.ServerInfo info = task.getValue();
            statusLabel.getStyleClass().setAll("meta-label", "status-ok");
            statusLabel.setText("Connected: " + info.name() + " v" + info.version()
                    + "  (protocol " + info.protocolVersion() + ")");
            logger.accept("MCP connected: " + info.name() + " v" + info.version());
            connectBtn.setDisable(false);
            loadAll();
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("meta-label", "status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            logger.accept("MCP connect FAILED: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        runBg(task);
    }

    /**
     * Builds the {@code Authorization} header from the token field, if filled. A bare token
     * is wrapped as {@code Bearer <token>}; a value that already names a scheme
     * (e.g. {@code Bearer …}, {@code Basic …}) is sent verbatim so other schemes still work.
     */
    private java.util.Map<String, String> authHeaders() {
        String token = tokenField.getText() == null ? "" : tokenField.getText().trim();
        if (token.isEmpty()) return java.util.Map.of();
        boolean hasScheme = token.matches("(?i)^(bearer|basic|token)\\s.+");
        return java.util.Map.of("Authorization", hasScheme ? token : "Bearer " + token);
    }

    private void loadAll() {
        // Only query capabilities the server advertised — calling an unadvertised method
        // (e.g. prompts/list on a tools-only server like Render) just returns an error.
        if (client.serverSupports("tools")) runList(() -> client.listTools(), toolList::getItems);
        if (client.serverSupports("resources")) runList(() -> client.listResources(), resourceList::getItems);
        else logger.accept("MCP: server does not advertise 'resources' — skipping");
        if (client.serverSupports("prompts")) runList(() -> client.listPrompts(), promptList::getItems);
        else logger.accept("MCP: server does not advertise 'prompts' — skipping");
    }

    private <T> void runList(java.util.concurrent.Callable<List<T>> supplier,
                             java.util.function.Supplier<javafx.collections.ObservableList<T>> target) {
        Task<List<T>> task = new Task<>() {
            @Override protected List<T> call() throws Exception { return supplier.call(); }
        };
        task.setOnSucceeded(e -> target.get().setAll(task.getValue()));
        task.setOnFailed(e -> logger.accept("MCP list failed: " + task.getException().getMessage()));
        runBg(task);
    }

    // ---- tabs ----

    private TabPane buildTabs() {
        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("editor-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab("Tools", buildToolsPane()),
                new Tab("Resources", buildResourcesPane()),
                new Tab("Prompts", buildPromptsPane()));
        return tabs;
    }

    private SplitPane buildToolsPane() {
        toolList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(McpTypes.Tool t, boolean empty) {
                super.updateItem(t, empty);
                setText(empty || t == null ? null : t.name());
            }
        });
        toolArgs.getStyleClass().add("code-area");
        toolArgs.setPromptText("{ }  — JSON arguments");
        toolArgs.setPrefRowCount(10);
        toolArgs.setMinHeight(140);
        toolResult.getStyleClass().add("code-area");
        toolResult.setEditable(false);
        toolResult.setPrefRowCount(8);
        toolResult.setMinHeight(120);

        Label desc = new Label();
        desc.getStyleClass().add("meta-label");
        desc.setWrapText(true);
        // A long tool description (Render's are paragraphs) must not crowd out the args box:
        // cap it and let it scroll within its own fixed band.
        ScrollPane descScroll = new ScrollPane(desc);
        descScroll.getStyleClass().add("desc-scroll");
        descScroll.setFitToWidth(true);
        descScroll.setMaxHeight(90);
        descScroll.setPrefHeight(60);
        toolList.getSelectionModel().selectedItemProperty().addListener((o, ov, t) -> {
            if (t != null) {
                desc.setText(t.description() + paramsHint(t.inputSchema()));
                toolArgs.setText(templateFromSchema(t.inputSchema()));
            }
        });

        Button callBtn = new Button("Call Tool");
        callBtn.getStyleClass().add("btn-primary");
        callBtn.setOnAction(e -> callSelectedTool());

        VBox right = new VBox(8, descScroll, new Label("Arguments (JSON):"), toolArgs, callBtn,
                new Label("Result:"), toolResult);
        right.setPadding(new Insets(8));
        VBox.setVgrow(toolArgs, Priority.ALWAYS);
        VBox.setVgrow(toolResult, Priority.ALWAYS);
        styleSmallLabels(right);

        SplitPane sp = new SplitPane(toolList, right);
        sp.setDividerPositions(0.3);
        return sp;
    }

    private void callSelectedTool() {
        McpTypes.Tool tool = toolList.getSelectionModel().getSelectedItem();
        if (tool == null || client == null) return;
        toolResult.setText("Calling…");
        logger.accept("MCP tools/call → " + tool.name());
        Task<McpTypes.ToolResult> task = new Task<>() {
            @Override protected McpTypes.ToolResult call() throws Exception {
                ObjectNode args = (ObjectNode) MAPPER.readTree(
                        toolArgs.getText().isBlank() ? "{}" : toolArgs.getText());
                return client.callTool(tool.name(), args);
            }
        };
        task.setOnSucceeded(e -> {
            McpTypes.ToolResult r = task.getValue();
            // isError=true is the SERVER's tool response (e.g. missing params, guidance) — not a
            // connection/transport failure. Make that distinction clear.
            StringBuilder sb = new StringBuilder(r.isError()
                    ? "⚠ The server returned a tool error (the call succeeded; this is the tool's response):\n\n"
                    : "");
            r.content().forEach(c -> sb.append(c.text()).append('\n'));
            toolResult.setText(sb.toString());
        });
        task.setOnFailed(e -> toolResult.setText("Error: " + task.getException().getMessage()));
        runBg(task);
    }

    /** One-line list of a tool's parameters (required marked with *). */
    private static String paramsHint(JsonNode schema) {
        if (schema == null || !schema.path("properties").isObject()) return "";
        java.util.Set<String> required = new java.util.LinkedHashSet<>();
        schema.path("required").forEach(n -> required.add(n.asText()));
        java.util.List<String> parts = new java.util.ArrayList<>();
        schema.get("properties").fieldNames().forEachRemaining(
                n -> parts.add(required.contains(n) ? n + "*" : n));
        return parts.isEmpty() ? "" : "\n\nParameters (*required): " + String.join(", ", parts);
    }

    /** Builds an editable JSON arguments template from the tool's input schema. */
    private static String templateFromSchema(JsonNode schema) {
        if (schema == null || !schema.path("properties").isObject()) return "{}";
        ObjectNode props = (ObjectNode) schema.get("properties");
        java.util.Set<String> required = new java.util.LinkedHashSet<>();
        schema.path("required").forEach(n -> required.add(n.asText()));

        java.util.List<String> names = new java.util.ArrayList<>();
        props.fieldNames().forEachRemaining(names::add);
        names.sort((a, b) -> Boolean.compare(!required.contains(a), !required.contains(b))); // required first

        ObjectNode out = MAPPER.createObjectNode();
        for (String name : names) {
            JsonNode p = props.path(name);
            if (p.has("default")) { out.set(name, p.get("default")); continue; }
            if (p.path("enum").isArray() && p.get("enum").size() > 0) { out.set(name, p.get("enum").get(0)); continue; }
            switch (p.path("type").asText("string")) {
                case "integer", "number" -> out.put(name, 0);
                case "boolean" -> out.put(name, false);
                case "array" -> out.set(name, MAPPER.createArrayNode());
                case "object" -> out.set(name, MAPPER.createObjectNode());
                default -> out.put(name, "");
            }
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        } catch (Exception e) {
            return "{}";
        }
    }

    private SplitPane buildResourcesPane() {
        resourceList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(McpTypes.Resource r, boolean empty) {
                super.updateItem(r, empty);
                setText(empty || r == null ? null : r.name() + "  (" + r.uri() + ")");
            }
        });
        resourceContent.getStyleClass().add("code-area");
        resourceContent.setEditable(false);

        Button readBtn = new Button("Read Resource");
        readBtn.getStyleClass().add("btn-primary");
        readBtn.setOnAction(e -> {
            McpTypes.Resource r = resourceList.getSelectionModel().getSelectedItem();
            if (r == null || client == null) return;
            resourceContent.setText("Reading…");
            Task<List<McpTypes.Content>> task = new Task<>() {
                @Override protected List<McpTypes.Content> call() { return client.readResource(r.uri()); }
            };
            task.setOnSucceeded(ev -> {
                StringBuilder sb = new StringBuilder();
                task.getValue().forEach(c -> sb.append(c.text()).append('\n'));
                resourceContent.setText(sb.toString());
            });
            task.setOnFailed(ev -> resourceContent.setText("Error: " + task.getException().getMessage()));
            runBg(task);
        });

        VBox right = new VBox(8, readBtn, resourceContent);
        right.setPadding(new Insets(8));
        VBox.setVgrow(resourceContent, Priority.ALWAYS);
        SplitPane sp = new SplitPane(resourceList, right);
        sp.setDividerPositions(0.35);
        return sp;
    }

    private SplitPane buildPromptsPane() {
        promptList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(McpTypes.Prompt p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : p.name());
            }
        });
        promptArgs.getStyleClass().add("code-area");
        promptArgs.setPromptText("{ }  — prompt arguments");
        promptResult.getStyleClass().add("code-area");
        promptResult.setEditable(false);

        Button getBtn = new Button("Get Prompt");
        getBtn.getStyleClass().add("btn-primary");
        getBtn.setOnAction(e -> {
            McpTypes.Prompt p = promptList.getSelectionModel().getSelectedItem();
            if (p == null || client == null) return;
            Task<List<McpTypes.Content>> task = new Task<>() {
                @Override protected List<McpTypes.Content> call() throws Exception {
                    ObjectNode args = (ObjectNode) MAPPER.readTree(
                            promptArgs.getText().isBlank() ? "{}" : promptArgs.getText());
                    return client.getPrompt(p.name(), args);
                }
            };
            task.setOnSucceeded(ev -> {
                StringBuilder sb = new StringBuilder();
                task.getValue().forEach(c -> sb.append("[").append(c.type()).append("]\n")
                        .append(c.text()).append("\n\n"));
                promptResult.setText(sb.toString());
            });
            task.setOnFailed(ev -> promptResult.setText("Error: " + task.getException().getMessage()));
            runBg(task);
        });

        VBox right = new VBox(8, new Label("Arguments (JSON):"), promptArgs, getBtn,
                new Label("Rendered:"), promptResult);
        right.setPadding(new Insets(8));
        VBox.setVgrow(promptArgs, Priority.ALWAYS);
        VBox.setVgrow(promptResult, Priority.ALWAYS);
        styleSmallLabels(right);
        SplitPane sp = new SplitPane(promptList, right);
        sp.setDividerPositions(0.3);
        return sp;
    }

    private void styleSmallLabels(VBox box) {
        box.getChildren().forEach(n -> {
            if (n instanceof Label l) l.getStyleClass().add("meta-label");
        });
    }

    private void runBg(Task<?> task) {
        Thread t = new Thread(task, "mcp-task");
        t.setDaemon(true);
        t.start();
    }
}
