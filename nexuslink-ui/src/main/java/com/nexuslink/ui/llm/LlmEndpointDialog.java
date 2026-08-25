package com.nexuslink.ui.llm;

import com.nexuslink.protocol.ai.llm.LlmEndpointConfig;
import com.nexuslink.protocol.ai.llm.LlmEndpointConfig.Api;
import com.nexuslink.protocol.ai.llm.LlmEndpointConfig.Auth;
import com.nexuslink.protocol.ai.llm.LlmEndpointStore;
import com.nexuslink.protocol.ai.llm.OidcTokenSource;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Configures an LLM endpoint: its URL and wire format, how it authenticates (including an OIDC
 * client-credentials exchange), any gateway headers, and every generation parameter.
 *
 * <p>Built for the case where the model is not reached at a vendor's public hostname — an internal
 * gateway, a self-hosted model, or a vendor account fronted by the company's own proxy — which the
 * SDK-based default path cannot express.
 *
 * <p>Secret fields accept either a literal value, kept only for this session, or an
 * {@code ${ENV_VAR}} reference, which is what gets saved. The dialog says so next to the fields,
 * because a silently-discarded password is worse than one the user knew wouldn't persist.
 */
final class LlmEndpointDialog {

    private final Dialog<ButtonType> dialog = new Dialog<>();

    private final TextField nameField = new TextField();
    private final ComboBox<Api> apiCombo = new ComboBox<>(FXCollections.observableArrayList(Api.values()));
    private final TextField baseUrlField = new TextField();
    private final TextField pathField = new TextField();
    private final TextField modelField = new TextField();

    private final ComboBox<Auth> authCombo = new ComboBox<>(FXCollections.observableArrayList(Auth.values()));
    private final TextField apiKeyHeaderField = new TextField();
    private final TextField apiKeyField = new TextField();
    private final TextField bearerField = new TextField();
    private final TextField tokenUrlField = new TextField();
    private final TextField clientIdField = new TextField();
    private final TextField clientSecretField = new TextField();
    private final TextField scopeField = new TextField();
    private final GridPane authGrid = new GridPane();

    private final TextField maxTokensField = new TextField();
    private final TextField temperatureField = new TextField();
    private final TextField topPField = new TextField();
    private final TextField topKField = new TextField();
    private final TextField stopField = new TextField();
    private final TextField thinkingField = new TextField();
    private final TextField anthropicVersionField = new TextField();
    private final TextField connectTimeoutField = new TextField();
    private final TextField readTimeoutField = new TextField();
    private final TextArea headersArea = new TextArea();

    private final Label validation = new Label();
    private final String existingId;

    private LlmEndpointDialog(Window owner, LlmEndpointConfig existing) {
        this.existingId = existing == null ? null : existing.id();

        dialog.initOwner(owner);
        dialog.setTitle(existing == null ? "New LLM Endpoint" : "Edit LLM Endpoint");
        dialog.setResizable(true);

        TabPane tabs = new TabPane(
                tab("Endpoint", endpointPane()),
                tab("Authentication", authPane()),
                tab("Parameters", parameterPane()),
                tab("Headers", headerPane()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Button testBtn = new Button("Test connection");
        testBtn.getStyleClass().add("btn-secondary");
        testBtn.setTooltip(new Tooltip("Sends a one-word prompt to verify URL, auth and model"));
        testBtn.setOnAction(e -> testConnection());

        validation.getStyleClass().add("meta-label");
        validation.setWrapText(true);
        validation.setMaxWidth(620);

        HBox actions = new HBox(8, testBtn, validation);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(8, 0, 0, 0));

        VBox root = new VBox(8, tabs, actions);
        root.setPadding(new Insets(14));
        root.setPrefWidth(680);

        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        apiCombo.valueProperty().addListener((o, old, api) -> onApiChanged(api));
        authCombo.valueProperty().addListener((o, old, auth) -> rebuildAuthFields(auth));

        populate(existing);
    }

    /** Opens the dialog; returns the configured endpoint, or empty if cancelled. */
    static Optional<LlmEndpointConfig> open(Window owner, LlmEndpointConfig existing) {
        LlmEndpointDialog d = new LlmEndpointDialog(owner, existing);
        while (d.dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            LlmEndpointConfig config = d.build();
            List<String> problems = config.validationErrors();
            if (problems.isEmpty()) return Optional.of(config);
            // Keep the dialog open rather than discarding a half-filled form.
            d.validation.getStyleClass().setAll("status-err");
            d.validation.setText(String.join("; ", problems));
        }
        return Optional.empty();
    }

    // ---- panes --------------------------------------------------------------

    private Tab tab(String title, Node content) {
        Tab t = new Tab(title, content);
        t.setClosable(false);
        return t;
    }

    private Node endpointPane() {
        nameField.setPromptText("Corp AI Gateway");
        baseUrlField.setPromptText("https://ai-gateway.corp.example.com");
        pathField.setPromptText("(defaults to the API's standard path)");
        modelField.setPromptText("claude-opus-4-8, or your gateway's model id");

        GridPane grid = form();
        grid.addRow(0, label("Name"), nameField);
        grid.addRow(1, label("API format"), apiCombo);
        grid.addRow(2, label("Base URL"), baseUrlField);
        grid.addRow(3, label("Path"), pathField);
        grid.addRow(4, label("Model"), modelField);

        Label note = new Label("""
                API format is the request/response shape the endpoint speaks, not the vendor: many \
                internal gateways expose an OpenAI-compatible API in front of other models. If \
                requests 404, the gateway is probably mounting the API somewhere other than the \
                default path — set Path explicitly.""");
        note.getStyleClass().add("meta-label");
        note.setWrapText(true);
        grid.add(note, 1, 5);
        return grid;
    }

    private Node authPane() {
        authGrid.setHgap(10);
        authGrid.setVgap(8);
        authGrid.setPadding(new Insets(14));

        VBox box = new VBox(8, rowOf(label("Method"), authCombo), authGrid);
        box.setPadding(new Insets(14, 0, 0, 0));
        return box;
    }

    private Node parameterPane() {
        maxTokensField.setPromptText(String.valueOf(LlmEndpointConfig.DEFAULT_MAX_TOKENS));
        temperatureField.setPromptText("unset — the endpoint's default");
        topPField.setPromptText("unset");
        topKField.setPromptText("unset (Anthropic only)");
        stopField.setPromptText("comma-separated stop sequences");
        thinkingField.setPromptText("extended-thinking budget in tokens (Anthropic only)");
        anthropicVersionField.setPromptText(LlmEndpointConfig.DEFAULT_ANTHROPIC_VERSION);
        connectTimeoutField.setPromptText("30000");
        readTimeoutField.setPromptText("120000");

        GridPane grid = form();
        grid.addRow(0, label("Max tokens"), maxTokensField);
        grid.addRow(1, label("Temperature"), temperatureField);
        grid.addRow(2, label("Top P"), topPField);
        grid.addRow(3, label("Top K"), topKField);
        grid.addRow(4, label("Stop sequences"), stopField);
        grid.addRow(5, label("Thinking budget"), thinkingField);
        grid.addRow(6, label("anthropic-version"), anthropicVersionField);
        grid.addRow(7, label("Connect timeout (ms)"), connectTimeoutField);
        grid.addRow(8, label("Read timeout (ms)"), readTimeoutField);

        Label note = new Label("Empty means the parameter is not sent at all, so the endpoint's own "
                + "default applies. Strict gateways reject unknown or null fields, so nothing is "
                + "invented on your behalf.");
        note.getStyleClass().add("meta-label");
        note.setWrapText(true);
        grid.add(note, 1, 9);
        return grid;
    }

    private Node headerPane() {
        headersArea.setPromptText("""
                One per line, Name: value

                X-Tenant-Id: acme
                X-Application: nexuslink""");
        headersArea.getStyleClass().add("code-area");
        headersArea.setPrefRowCount(10);

        Label note = new Label("Extra headers are sent with every request and override NexusLink's "
                + "defaults — for gateway routing, tenancy or cost-centre attribution.");
        note.getStyleClass().add("meta-label");
        note.setWrapText(true);

        VBox box = new VBox(8, headersArea, note);
        box.setPadding(new Insets(14));
        VBox.setVgrow(headersArea, Priority.ALWAYS);
        return box;
    }

    /** Rebuilds the auth tab's fields to show only what the chosen method needs. */
    private void rebuildAuthFields(Auth auth) {
        authGrid.getChildren().clear();
        if (auth == null) return;
        int row = 0;
        switch (auth) {
            case NONE -> authGrid.add(hint("No credential is sent. Use this when the gateway "
                    + "authenticates by network position or client certificate."), 0, row);
            case API_KEY -> {
                authGrid.addRow(row++, label("Header name"), apiKeyHeaderField);
                authGrid.addRow(row++, label("API key"), apiKeyField);
                authGrid.add(secretHint(), 1, row);
            }
            case BEARER -> {
                authGrid.addRow(row++, label("Bearer token"), bearerField);
                authGrid.add(secretHint(), 1, row);
            }
            case OIDC_CLIENT_CREDENTIALS -> {
                authGrid.addRow(row++, label("Token URL"), tokenUrlField);
                authGrid.addRow(row++, label("Client ID"), clientIdField);
                authGrid.addRow(row++, label("Client secret"), clientSecretField);
                authGrid.addRow(row++, label("Scope"), scopeField);
                authGrid.add(hint("NexusLink exchanges these for an access token at the token URL "
                        + "and sends it as a bearer token, refreshing it before it expires. "
                        + "Secrets follow the same rule as below."), 1, row++);
                authGrid.add(secretHint(), 1, row);
            }
        }
    }

    private void onApiChanged(Api api) {
        if (api == null) return;
        pathField.setPromptText(api.defaultPath());
        // Header name is vendor-specific; keep the user's value but suggest the right default.
        if (apiKeyHeaderField.getText().isBlank()) {
            apiKeyHeaderField.setPromptText(api == Api.ANTHROPIC ? "x-api-key" : "api-key");
        }
        boolean anthropic = api == Api.ANTHROPIC;
        topKField.setDisable(!anthropic);
        thinkingField.setDisable(!anthropic);
        anthropicVersionField.setDisable(!anthropic);
    }

    // ---- populate / build ---------------------------------------------------

    private void populate(LlmEndpointConfig c) {
        if (c == null) {
            apiCombo.setValue(Api.ANTHROPIC);
            authCombo.setValue(Auth.NONE);
            return;
        }
        nameField.setText(c.name());
        apiCombo.setValue(c.api());
        baseUrlField.setText(c.baseUrl());
        pathField.setText(c.path());
        modelField.setText(c.model());

        authCombo.setValue(c.auth());
        apiKeyHeaderField.setText(c.apiKeyHeader());
        apiKeyField.setText(nullToEmpty(c.apiKey()));
        bearerField.setText(nullToEmpty(c.bearerToken()));
        tokenUrlField.setText(nullToEmpty(c.tokenUrl()));
        clientIdField.setText(nullToEmpty(c.clientId()));
        clientSecretField.setText(nullToEmpty(c.clientSecret()));
        scopeField.setText(nullToEmpty(c.scope()));

        maxTokensField.setText(String.valueOf(c.maxTokens()));
        temperatureField.setText(c.temperature() == null ? "" : String.valueOf(c.temperature()));
        topPField.setText(c.topP() == null ? "" : String.valueOf(c.topP()));
        topKField.setText(c.topK() == null ? "" : String.valueOf(c.topK()));
        stopField.setText(String.join(", ", c.stopSequences()));
        thinkingField.setText(c.thinkingBudgetTokens() == null ? "" : String.valueOf(c.thinkingBudgetTokens()));
        anthropicVersionField.setText(c.anthropicVersion());
        connectTimeoutField.setText(String.valueOf(c.connectTimeoutMs()));
        readTimeoutField.setText(String.valueOf(c.readTimeoutMs()));

        StringBuilder headers = new StringBuilder();
        c.extraHeaders().forEach((k, v) -> headers.append(k).append(": ").append(v).append('\n'));
        headersArea.setText(headers.toString());
    }

    /** Assembles the config from the form. Blank numeric fields stay null so they aren't sent. */
    private LlmEndpointConfig build() {
        List<String> stops = new ArrayList<>();
        for (String s : stopField.getText().split(",")) {
            if (!s.isBlank()) stops.add(s.trim());
        }
        return new LlmEndpointConfig(
                existingId, nameField.getText().trim(), apiCombo.getValue(),
                baseUrlField.getText().trim(), pathField.getText().trim(), authCombo.getValue(),
                apiKeyHeaderField.getText().trim(), blankToNull(apiKeyField.getText()),
                blankToNull(bearerField.getText()), blankToNull(tokenUrlField.getText()),
                blankToNull(clientIdField.getText()), blankToNull(clientSecretField.getText()),
                blankToNull(scopeField.getText()), anthropicVersionField.getText().trim(),
                parseHeaders(headersArea.getText()), modelField.getText().trim(),
                parseLong(maxTokensField.getText(), 0),
                parseDouble(temperatureField.getText()), parseDouble(topPField.getText()),
                parseInt(topKField.getText()), stops, parseInt(thinkingField.getText()),
                (int) parseLong(connectTimeoutField.getText(), 0),
                (int) parseLong(readTimeoutField.getText(), 0));
    }

    /** Sends a minimal prompt so the user finds out here whether the endpoint works. */
    private void testConnection() {
        LlmEndpointConfig config = build();
        List<String> problems = config.validationErrors();
        if (!problems.isEmpty()) {
            validation.getStyleClass().setAll("status-err");
            validation.setText(String.join("; ", problems));
            return;
        }
        validation.getStyleClass().setAll("meta-label");
        validation.setText("Testing " + config.requestUrl() + "…");

        // Credentials may have changed since the last call; drop any cached OIDC token.
        OidcTokenSource.clearCache();

        javafx.concurrent.Task<com.nexuslink.protocol.ai.llm.AnthropicService.Result> task =
                new javafx.concurrent.Task<>() {
                    @Override protected com.nexuslink.protocol.ai.llm.AnthropicService.Result call() {
                        return com.nexuslink.protocol.ai.llm.HttpLlmClient.complete(
                                config, null, "Reply with the single word: ok");
                    }
                };
        task.setOnSucceeded(e -> {
            var r = task.getValue();
            validation.getStyleClass().setAll(r.success() ? "status-2xx" : "status-err");
            validation.setText(r.success()
                    ? "✓ Connected — " + r.durationMs() + " ms, " + r.outputTokens() + " output tokens"
                    : "✖ " + r.error());
        });
        task.setOnFailed(e -> {
            validation.getStyleClass().setAll("status-err");
            validation.setText("✖ " + task.getException());
        });
        Thread t = new Thread(task, "llm-endpoint-test");
        t.setDaemon(true);
        t.start();
    }

    // ---- small helpers ------------------------------------------------------

    private GridPane form() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(150);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, fields);
        return grid;
    }

    private HBox rowOf(Node... nodes) {
        HBox row = new HBox(10, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, 14, 0, 14));
        return row;
    }

    private Label label(String text) {
        Label l = new Label(text + ":");
        l.getStyleClass().add("meta-label");
        return l;
    }

    private Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("meta-label");
        l.setWrapText(true);
        l.setMaxWidth(460);
        return l;
    }

    private Label secretHint() {
        return hint("Type a value to use it for this session only, or write ${ENV_VAR} to read it "
                + "from the environment. Only the ${ENV_VAR} form is saved — literal secrets are "
                + "never written to disk.");
    }

    private static Map<String, String> parseHeaders(String text) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (!name.isEmpty()) headers.put(name, value);
        }
        return headers;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Double parseDouble(String s) {
        try {
            return s == null || s.isBlank() ? null : Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String s) {
        try {
            return s == null || s.isBlank() ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parseLong(String s, long fallback) {
        try {
            return s == null || s.isBlank() ? fallback : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
