package com.nexuslink.ui.graphql;

import com.nexuslink.protocol.http.graphql.GraphQLService;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.Map;
import java.util.function.Consumer;

/**
 * GraphQL client tab — point at an endpoint, write a query (and optional variables), run it, and
 * read the pretty-printed JSON response. Includes a one-click schema introspection.
 */
public final class GraphQLView extends BorderPane {

    private final GraphQLService service = new GraphQLService();

    private final TextField endpointField = new TextField("https://countries.trevorblades.com/");
    private final TextArea queryEditor = new TextArea();
    private final TextArea variablesEditor = new TextArea();
    private final TextArea responseArea = new TextArea();
    private final Label statusLabel = new Label("Ready");

    private Consumer<String> logger = s -> {};

    public GraphQLView() {
        getStyleClass().add("graphql-view");
        setTop(buildBar());
        setCenter(buildBody());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    /** Pre-fills the endpoint (used when opening a saved/sample connection). */
    public void prefill(String endpoint) {
        if (endpoint != null && !endpoint.isBlank()) endpointField.setText(endpoint);
    }

    private VBox buildBar() {
        endpointField.getStyleClass().add("nl-field");
        endpointField.setPromptText("https://api.example.com/graphql");
        HBox.setHgrow(endpointField, Priority.ALWAYS);

        Button runBtn = new Button("Run  (Ctrl+Enter)");
        runBtn.getStyleClass().add("btn-primary");
        runBtn.setOnAction(e -> run());

        Button introspectBtn = new Button("Introspect");
        introspectBtn.getStyleClass().add("btn-secondary");
        introspectBtn.setTooltip(new Tooltip("Fetch the schema (types + root operations)"));
        introspectBtn.setOnAction(e -> {
            queryEditor.setText(GraphQLService.INTROSPECTION_QUERY);
            run();
        });

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("rest-client"));

        HBox row = new HBox(8, new Label("Endpoint:"), endpointField, runBtn, introspectBtn, helpBtn);
        ((Label) row.getChildren().get(0)).getStyleClass().add("meta-label");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row, statusRow);
    }

    private SplitPane buildBody() {
        queryEditor.getStyleClass().add("code-area");
        queryEditor.setPromptText("query { ... }   (Ctrl+Enter to run)");
        queryEditor.setText("{\n  countries {\n    code\n    name\n    emoji\n  }\n}");
        queryEditor.setOnKeyPressed(e -> {
            if (e.isShortcutDown() && e.getCode() == javafx.scene.input.KeyCode.ENTER) run();
        });

        variablesEditor.getStyleClass().add("code-area");
        variablesEditor.setPromptText("variables (JSON, optional) — e.g. { \"code\": \"DE\" }");
        variablesEditor.setPrefRowCount(5);

        Label qLbl = sectionLabel("QUERY");
        Label vLbl = sectionLabel("VARIABLES");
        VBox left = new VBox(4, qLbl, queryEditor, vLbl, variablesEditor);
        left.setPadding(new Insets(8));
        VBox.setVgrow(queryEditor, Priority.ALWAYS);

        responseArea.getStyleClass().add("code-area");
        responseArea.setEditable(false);
        responseArea.setPromptText("Response appears here…");
        Label rLbl = sectionLabel("RESPONSE");
        VBox right = new VBox(4, rLbl, responseArea);
        right.setPadding(new Insets(8));
        VBox.setVgrow(responseArea, Priority.ALWAYS);

        SplitPane sp = new SplitPane(left, right);
        sp.setOrientation(Orientation.HORIZONTAL);
        sp.setDividerPositions(0.45);
        return sp;
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-title");
        return l;
    }

    private void run() {
        String rawEndpoint = endpointField.getText().trim();
        if (rawEndpoint.isEmpty()) { statusLabel.setText("Enter a GraphQL endpoint first"); return; }
        // Resolve ${VAR} against the active environment for the endpoint, query, and variables.
        String endpoint = com.nexuslink.ui.env.Env.resolve(rawEndpoint);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Running…");
        logger.accept("GraphQL → " + endpoint);

        String query = com.nexuslink.ui.env.Env.resolve(queryEditor.getText());
        String variables = com.nexuslink.ui.env.Env.resolve(variablesEditor.getText());
        Task<GraphQLService.Result> task = new Task<>() {
            @Override protected GraphQLService.Result call() {
                return service.execute(endpoint, query, variables, Map.of());
            }
        };
        task.setOnSucceeded(e -> {
            GraphQLService.Result r = task.getValue();
            if (r.failed()) {
                statusLabel.getStyleClass().setAll("status-err");
                statusLabel.setText("✖ " + r.error());
                responseArea.setText("Error: " + r.error());
                return;
            }
            statusLabel.getStyleClass().setAll(r.status() / 100 == 2 ? "status-2xx" : "status-4xx");
            statusLabel.setText("HTTP " + r.status() + " · " + r.durationMs() + " ms");
            responseArea.setText(r.body());
            logger.accept("GraphQL ok — HTTP " + r.status() + " (" + r.durationMs() + " ms)");
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("✖ " + task.getException().getMessage());
        });
        Thread t = new Thread(task, "graphql-task");
        t.setDaemon(true);
        t.start();
    }
}
