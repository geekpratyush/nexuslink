package com.nexuslink.ui.llm;

import com.nexuslink.protocol.ai.llm.AnthropicService;
import com.nexuslink.protocol.ai.llm.HttpLlmClient;
import com.nexuslink.protocol.ai.llm.LlmEndpointConfig;
import com.nexuslink.protocol.ai.llm.LlmEndpointStore;
import com.nexuslink.protocol.ai.llm.OidcTokenSource;
import com.nexuslink.ui.env.Env;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AI Agent / LLM tester tab — sends single-turn requests to a model and shows the reply, timing
 * and token usage.
 *
 * <p>Two ways to reach a model. The default, <em>Anthropic (SDK)</em>, uses the official Java SDK
 * with {@code ANTHROPIC_API_KEY} from the environment — the right path for an individual developer.
 * The other is a configured {@link LlmEndpointConfig}: an explicit URL, wire format, auth method
 * (including an OIDC client-credentials exchange) and full parameter set, which is what reaching a
 * model through an organisation's internal gateway actually requires.
 */
public final class LlmTesterView extends BorderPane {

    private final AnthropicService service = new AnthropicService();
    private final LlmEndpointStore endpoints = new LlmEndpointStore();

    /**
     * Endpoints as the user configured them this session, including any literal secret they typed.
     * The store deliberately strips those before writing to disk, so this is what calls must use.
     */
    private final Map<String, LlmEndpointConfig> sessionConfigs = new LinkedHashMap<>();

    /** The endpoint picker. A null value means the built-in Anthropic SDK path. */
    private final ComboBox<LlmEndpointConfig> endpointCombo = new ComboBox<>();
    private final ComboBox<String> modelCombo = new ComboBox<>();
    private final TextArea systemPrompt = new TextArea();
    private final TextArea userMessage = new TextArea();
    private final TextArea responseArea = new TextArea();
    private final Button sendBtn = new Button("Send");
    private final Label statusLabel = new Label();
    private final ProgressBar progress = new ProgressBar();

    private Consumer<String> logger = s -> {};

    public LlmTesterView() {
        getStyleClass().add("llm-view");
        setTop(buildBar());
        setCenter(buildBody());
        refreshKeyStatus();
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    private VBox buildBar() {
        modelCombo.getItems().addAll(AnthropicService.MODELS);
        modelCombo.setValue(AnthropicService.DEFAULT_MODEL);
        modelCombo.setPrefWidth(180);

        endpointCombo.setPrefWidth(200);
        // The SDK path is the null item; JavaFX renders a null value as an empty button cell, so
        // the prompt text carries the label in that state.
        endpointCombo.setPromptText("Anthropic (SDK)");
        endpointCombo.setButtonCell(endpointCell());
        endpointCombo.setCellFactory(lv -> endpointCell());
        endpointCombo.valueProperty().addListener((o, old, endpoint) -> onEndpointSelected(endpoint));
        reloadEndpoints(null);

        Button configureBtn = new Button("Configure…");
        configureBtn.getStyleClass().add("btn-secondary");
        configureBtn.setTooltip(new Tooltip("Add or edit an endpoint: URL, API format, auth "
                + "(API key / bearer / OIDC) and generation parameters"));
        configureBtn.setOnAction(e -> configureEndpoint());

        Button deleteBtn = new Button("Remove");
        deleteBtn.getStyleClass().add("btn-secondary");
        deleteBtn.setTooltip(new Tooltip("Remove the selected endpoint"));
        deleteBtn.setOnAction(e -> removeEndpoint());

        sendBtn.getStyleClass().add("btn-primary");
        sendBtn.setOnAction(e -> send());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("llm-endpoints"));

        Label endpointLbl = new Label("Endpoint:");
        endpointLbl.getStyleClass().add("meta-label");
        Label modelLbl = new Label("Model:");
        modelLbl.getStyleClass().add("meta-label");
        HBox row = new HBox(8, endpointLbl, endpointCombo, configureBtn, deleteBtn,
                modelLbl, modelCombo, sendBtn, helpBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));

        statusLabel.getStyleClass().add("meta-label");
        progress.setVisible(false);
        progress.setManaged(false);
        progress.setPrefHeight(3);
        progress.setMaxWidth(Double.MAX_VALUE);
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 4, 10));

        return new VBox(row, progress, statusRow);
    }

    private SplitPane buildBody() {
        systemPrompt.getStyleClass().add("code-area");
        systemPrompt.setPromptText("System prompt (optional) — e.g. \"You are a terse assistant.\"");
        systemPrompt.setPrefRowCount(3);

        userMessage.getStyleClass().add("code-area");
        userMessage.setPromptText("User message — what to ask the model…");
        userMessage.setText("In one sentence, what is the Model Context Protocol?");

        VBox input = new VBox(6,
                label("System"), systemPrompt,
                label("User message"), userMessage);
        input.setPadding(new Insets(8));
        VBox.setVgrow(userMessage, Priority.ALWAYS);

        responseArea.getStyleClass().add("code-area");
        responseArea.setEditable(false);
        responseArea.setPromptText("Response appears here…");
        VBox output = new VBox(6, label("Response"), responseArea);
        output.setPadding(new Insets(8));
        VBox.setVgrow(responseArea, Priority.ALWAYS);

        SplitPane sp = new SplitPane(input, output);
        sp.setOrientation(javafx.geometry.Orientation.VERTICAL);
        sp.setDividerPositions(0.45);
        return sp;
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("meta-label");
        return l;
    }

    // ---- endpoints ----------------------------------------------------------

    /** Renders "Anthropic (SDK)" for the built-in path, otherwise the endpoint's name. */
    private ListCell<LlmEndpointConfig> endpointCell() {
        return new ListCell<>() {
            @Override protected void updateItem(LlmEndpointConfig c, boolean empty) {
                super.updateItem(c, empty);
                setText(empty ? null : (c == null ? "Anthropic (SDK)" : c.name()));
            }
        };
    }

    /** Rebuilds the picker from the store; the null entry is the built-in SDK path. */
    private void reloadEndpoints(String selectId) {
        List<LlmEndpointConfig> items = new ArrayList<>();
        items.add(null);
        items.addAll(endpoints.all());
        endpointCombo.getItems().setAll(items);

        items.stream().filter(c -> c != null && c.id().equals(selectId)).findFirst()
                .ifPresentOrElse(endpointCombo.getSelectionModel()::select,
                        () -> endpointCombo.getSelectionModel().selectFirst());
    }

    /**
     * A configured endpoint carries its own model id, so the model picker only applies to the SDK
     * path — showing an editable model list alongside an endpoint that ignores it would mislead.
     */
    private void onEndpointSelected(LlmEndpointConfig endpoint) {
        boolean sdk = endpoint == null;
        modelCombo.setDisable(!sdk);
        if (sdk) {
            refreshKeyStatus();
        } else {
            statusLabel.getStyleClass().setAll("meta-label");
            statusLabel.setText("● " + endpoint.name() + " — " + endpoint.api() + " at "
                    + endpoint.requestUrl() + ", model " + endpoint.model()
                    + ", auth " + endpoint.auth());
        }
    }

    private void configureEndpoint() {
        LlmEndpointConfig selected = endpointCombo.getValue();
        var configured = LlmEndpointDialog.open(
                getScene() == null ? null : getScene().getWindow(), selected);
        if (configured.isEmpty()) return;
        try {
            // Credentials may have changed; make the next call re-authenticate.
            OidcTokenSource.clearCache();
            var saved = endpoints.save(configured.get());
            // Keep the session's literal secrets in memory: only ${ENV_VAR} refs were persisted.
            sessionConfigs.put(saved.id(), withId(configured.get(), saved.id()));
            reloadEndpoints(saved.id());
        } catch (RuntimeException e) {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Couldn't save the endpoint: " + e.getMessage());
        }
    }

    private void removeEndpoint() {
        LlmEndpointConfig selected = endpointCombo.getValue();
        if (selected == null) {
            statusLabel.getStyleClass().setAll("meta-label");
            statusLabel.setText("The built-in Anthropic (SDK) entry can't be removed");
            return;
        }
        endpoints.delete(selected.id());
        sessionConfigs.remove(selected.id());
        reloadEndpoints(null);
    }

    /**
     * The config to actually call with: the session copy when one exists — it still holds any
     * literal secret the user typed — otherwise the stored one.
     */
    private LlmEndpointConfig effective(LlmEndpointConfig selected) {
        if (selected == null) return null;
        return sessionConfigs.getOrDefault(selected.id(), selected);
    }

    private static LlmEndpointConfig withId(LlmEndpointConfig c, String id) {
        return new LlmEndpointConfig(id, c.name(), c.api(), c.baseUrl(), c.path(), c.auth(),
                c.apiKeyHeader(), c.apiKey(), c.bearerToken(), c.tokenUrl(), c.clientId(),
                c.clientSecret(), c.scope(), c.anthropicVersion(), c.extraHeaders(), c.model(),
                c.maxTokens(), c.temperature(), c.topP(), c.topK(), c.stopSequences(),
                c.thinkingBudgetTokens(), c.connectTimeoutMs(), c.readTimeoutMs());
    }

    private void refreshKeyStatus() {
        if (service.isConfigured()) {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("● ANTHROPIC_API_KEY detected — ready");
        } else {
            statusLabel.getStyleClass().setAll("status-4xx");
            statusLabel.setText("● ANTHROPIC_API_KEY not set — export it and reopen this tab to make live calls");
        }
    }

    private void send() {
        String msg = Env.resolve(userMessage.getText().trim());   // resolve ${VAR} in the prompt
        if (msg.isEmpty()) { statusLabel.setText("Enter a user message first"); return; }

        LlmEndpointConfig endpoint = effective(endpointCombo.getValue());
        String target = endpoint == null ? modelCombo.getValue() : endpoint.model() + " @ " + endpoint.name();

        sendBtn.setDisable(true);
        progress.setVisible(true);
        progress.setManaged(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Calling " + target + "…");
        responseArea.clear();
        logger.accept("LLM " + target + " ← " + truncate(msg));

        Task<AnthropicService.Result> task = new Task<>() {
            @Override protected AnthropicService.Result call() {
                String system = Env.resolve(systemPrompt.getText());
                // A configured endpoint owns its model and parameters; the SDK path uses the picker.
                return endpoint == null
                        ? service.complete(modelCombo.getValue(), system, msg)
                        : HttpLlmClient.complete(endpoint, system, msg);
            }
        };
        task.setOnSucceeded(e -> { render(task.getValue()); finish(); });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Error: " + task.getException());
            finish();
        });
        Thread t = new Thread(task, "llm-task");
        t.setDaemon(true);
        t.start();
    }

    private void render(AnthropicService.Result r) {
        if (!r.success()) {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("✖ " + r.error());
            responseArea.setText(r.error() + "\n\nPress F1 for setup help.");
            logger.accept("LLM FAILED — " + r.error());
            return;
        }
        statusLabel.getStyleClass().setAll("status-2xx");
        statusLabel.setText("✓ " + r.durationMs() + " ms   ·   in " + r.inputTokens()
                + " tok / out " + r.outputTokens() + " tok"
                + (r.stopReason().isBlank() ? "" : "   ·   stop: " + r.stopReason()));
        responseArea.setText(r.text());
        logger.accept("LLM ok — " + r.outputTokens() + " out tok, " + r.durationMs() + "ms");
    }

    private void finish() {
        sendBtn.setDisable(false);
        progress.setVisible(false);
        progress.setManaged(false);
    }

    private String truncate(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
