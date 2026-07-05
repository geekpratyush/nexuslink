package com.nexuslink.ui.graphql;

import com.nexuslink.protocol.http.graphql.GraphQLSchema;
import com.nexuslink.protocol.http.graphql.GraphQLService;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
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
    private final org.fxmisc.richtext.CodeArea responseArea = com.nexuslink.ui.util.JsonView.plainArea(false);
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

        Button schemaBtn = new Button("Schema…");
        schemaBtn.getStyleClass().add("btn-secondary");
        schemaBtn.setTooltip(new Tooltip("Browse the schema's types and fields; double-click a field to insert it"));
        schemaBtn.setOnAction(e -> fetchSchema());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("rest-client"));

        HBox row = new HBox(8, new Label("Endpoint:"), endpointField, runBtn, introspectBtn, schemaBtn, helpBtn);
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

        Label rLbl = sectionLabel("RESPONSE");
        org.fxmisc.flowless.VirtualizedScrollPane<org.fxmisc.richtext.CodeArea> responseScroll =
                new org.fxmisc.flowless.VirtualizedScrollPane<>(responseArea);
        VBox right = new VBox(4, rLbl, responseScroll);
        right.setPadding(new Insets(8));
        VBox.setVgrow(responseScroll, Priority.ALWAYS);

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

    /** Runs the introspection query off-thread, parses it into a {@link GraphQLSchema}, and shows the explorer. */
    private void fetchSchema() {
        String rawEndpoint = endpointField.getText().trim();
        if (rawEndpoint.isEmpty()) { statusLabel.setText("Enter a GraphQL endpoint first"); return; }
        String endpoint = com.nexuslink.ui.env.Env.resolve(rawEndpoint);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Fetching schema…");
        Task<GraphQLSchema> task = new Task<>() {
            @Override protected GraphQLSchema call() throws Exception {
                GraphQLService.Result r = service.execute(endpoint, GraphQLService.INTROSPECTION_QUERY, null, Map.of());
                if (r.failed()) throw new IllegalStateException(r.error());
                return GraphQLSchema.parse(r.body());
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.setText("Schema loaded");
            showSchemaExplorer(task.getValue());
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("✖ schema: " + task.getException().getMessage());
        });
        Thread t = new Thread(task, "graphql-schema");
        t.setDaemon(true);
        t.start();
    }

    /**
     * A two-pane schema explorer: type names on the left, the selected type's fields (name · type ·
     * args) on the right. Double-clicking a field inserts its name into the query editor at the caret,
     * a lightweight form of schema-aware completion.
     */
    private void showSchemaExplorer(GraphQLSchema schema) {
        ListView<String> types = new ListView<>(FXCollections.observableArrayList(schema.typeNames()));
        types.setPrefWidth(200);

        TableView<GraphQLSchema.Field> fields = new TableView<>();
        fields.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        fields.setPlaceholder(new Label("Select a type"));
        com.nexuslink.ui.util.TableContextMenus.installCopy(fields);
        TableColumn<GraphQLSchema.Field, String> fName = new TableColumn<>("Field");
        fName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        TableColumn<GraphQLSchema.Field, String> fType = new TableColumn<>("Type");
        fType.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().typeName()));
        TableColumn<GraphQLSchema.Field, String> fArgs = new TableColumn<>("Args");
        fArgs.setCellValueFactory(c -> new SimpleStringProperty(String.join(", ", c.getValue().argNames())));
        fields.getColumns().add(fName);
        fields.getColumns().add(fType);
        fields.getColumns().add(fArgs);

        types.getSelectionModel().selectedItemProperty().addListener((o, a, t) ->
                fields.setItems(FXCollections.observableArrayList(
                        schema.type(t).map(GraphQLSchema.Type::fields).orElse(java.util.List.of()))));
        // Preselect the Query root type so the common case is one click away.
        schema.rootTypeName(GraphQLSchema.OperationType.QUERY).ifPresent(types.getSelectionModel()::select);

        fields.setRowFactory(tv -> {
            TableRow<GraphQLSchema.Field> r = new TableRow<>();
            r.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !r.isEmpty()) {
                    queryEditor.insertText(queryEditor.getCaretPosition(), r.getItem().name());
                    queryEditor.requestFocus();
                }
            });
            return r;
        });

        SplitPane sp = new SplitPane(types, fields);
        sp.setDividerPositions(0.32);
        sp.setPrefSize(640, 420);

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("GraphQL schema");
        String q = schema.rootTypeName(GraphQLSchema.OperationType.QUERY).orElse("?");
        String m = schema.rootTypeName(GraphQLSchema.OperationType.MUTATION).orElse("—");
        dialog.setHeaderText("Query: " + q + "   ·   Mutation: " + m + "   ·   " + schema.typeNames().size() + " types");
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.getDialogPane().setContent(sp);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().sceneProperty().addListener((o, ov, sc) -> {
            if (sc != null) com.nexuslink.ui.theme.ThemeManager.get().register(sc);
        });
        dialog.showAndWait();
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
                com.nexuslink.ui.util.JsonView.setSmart(responseArea, "Error: " + r.error());
                return;
            }
            statusLabel.getStyleClass().setAll(r.status() / 100 == 2 ? "status-2xx" : "status-4xx");
            statusLabel.setText("HTTP " + r.status() + " · " + r.durationMs() + " ms");
            com.nexuslink.ui.util.JsonView.setSmart(responseArea, r.body());
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
