package com.nexuslink.ui.llm;

import com.nexuslink.protocol.ai.llm.AnthropicService;
import com.nexuslink.ui.env.Env;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.function.Consumer;

/**
 * AI Agent / LLM tester tab — sends single-turn Messages API requests to Claude via the
 * Anthropic Java SDK. Defaults to {@code claude-opus-4-8} with adaptive thinking.
 * Requires {@code ANTHROPIC_API_KEY} in the environment to make live calls.
 */
public final class LlmTesterView extends BorderPane {

    private final AnthropicService service = new AnthropicService();

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

        sendBtn.getStyleClass().add("btn-primary");
        sendBtn.setOnAction(e -> send());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("plugins"));

        Label modelLbl = new Label("Model:");
        modelLbl.getStyleClass().add("meta-label");
        HBox row = new HBox(8, modelLbl, modelCombo, sendBtn, helpBtn);
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

        sendBtn.setDisable(true);
        progress.setVisible(true);
        progress.setManaged(true);
        statusLabel.setText("Calling " + modelCombo.getValue() + "…");
        responseArea.clear();
        logger.accept("LLM " + modelCombo.getValue() + " ← " + truncate(msg));

        Task<AnthropicService.Result> task = new Task<>() {
            @Override protected AnthropicService.Result call() {
                return service.complete(modelCombo.getValue(), Env.resolve(systemPrompt.getText()), msg);
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
