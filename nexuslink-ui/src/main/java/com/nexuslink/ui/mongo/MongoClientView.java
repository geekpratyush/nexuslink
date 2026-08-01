package com.nexuslink.ui.mongo;

import com.nexuslink.core.connection.AuthMethod;
import com.nexuslink.core.connection.ConnectionProfile;
import com.nexuslink.plugin.ResourceNode;
import com.nexuslink.protocol.mongo.MongoExplorer;
import com.nexuslink.protocol.mongo.MongoQueryResult;
import com.nexuslink.protocol.mongo.MongoService;
import com.nexuslink.ui.env.Env;
import com.nexuslink.ui.explorer.ResourceExplorerView;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;
import java.util.function.Consumer;

/**
 * MongoDB client tab — connect with a connection string, then browse the live object tree
 * (databases → collections → indexes, with collStats in the details panel) and run
 * find / aggregate / insert / update / delete operations using Extended-JSON.
 */
public final class MongoClientView extends BorderPane {

    private final MongoService service = new MongoService();
    private final MongoExplorer explorerModel = new MongoExplorer(service);
    private final ResourceExplorerView explorer = new ResourceExplorerView("Databases");

    private final TextField connField = new TextField("mongodb://localhost:27017");
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    private final ComboBox<String> modeCombo = new ComboBox<>();
    private final TextField limitField = new TextField("100");
    private final TextArea queryEditor = new TextArea();
    private final org.fxmisc.richtext.CodeArea resultArea = com.nexuslink.ui.util.JsonView.area(false);
    private final org.fxmisc.flowless.VirtualizedScrollPane<org.fxmisc.richtext.CodeArea> resultScroll =
            new org.fxmisc.flowless.VirtualizedScrollPane<>(resultArea);
    private final Label resultStatus = new Label();

    // Compass-like result views
    private final ComboBox<String> viewCombo = new ComboBox<>();
    private final TableView<org.bson.Document> docTable = new TableView<>();
    private final TableView<String[]> schemaTable = new TableView<>();
    private final java.util.List<org.bson.Document> lastDocs = new java.util.ArrayList<>();
    private String lastCollection;   // source collection for the cached docs (editable when set)

    private final MenuButton recentBtn = new MenuButton("Recent");
    private final java.util.LinkedList<String[]> recentQueries = new java.util.LinkedList<>();

    private String activeDb;
    private String activeCollection;

    private Consumer<String> logger = s -> {};
    private Consumer<ConnectionProfile> onSave = p -> {};

    public MongoClientView() {
        getStyleClass().add("mongo-view");
        setTop(buildConnectionBar());
        setCenter(buildBody());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
        explorer.setLogger(this.logger);
    }

    /** Notified when the user saves the current connection as a profile. */
    public void setOnSave(Consumer<ConnectionProfile> onSave) {
        this.onSave = onSave == null ? p -> {} : onSave;
    }

    /** Pre-fills the connection string (used when opening a saved/sample connection). */
    public void prefill(String connectionString) {
        if (connectionString != null && !connectionString.isBlank()) connField.setText(connectionString);
    }

    private void editDocument(org.bson.Document doc) {
        if (doc == null) return;
        if (lastCollection == null) { statusLabel.setText("Editing is available for find results"); return; }
        Object id = doc.get("_id");
        Dialog<ButtonType> d = new Dialog<>();
        if (getScene() != null) d.initOwner(getScene().getWindow());
        d.setTitle("Edit document");
        d.setHeaderText("Edit and save (matched by _id) in '" + lastCollection + "'");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setOnShown(ev -> { if (d.getDialogPane().getScene() != null) com.nexuslink.ui.theme.ThemeManager.get().register(d.getDialogPane().getScene()); });
        org.fxmisc.richtext.CodeArea editor = com.nexuslink.ui.util.JsonView.area(true);
        com.nexuslink.ui.util.JsonView.setText(editor,
                doc.toJson(org.bson.json.JsonWriterSettings.builder().indent(true).build()));
        org.fxmisc.flowless.VirtualizedScrollPane<org.fxmisc.richtext.CodeArea> editorScroll =
                new org.fxmisc.flowless.VirtualizedScrollPane<>(editor);
        editorScroll.setPrefSize(560, 360);
        d.getDialogPane().setContent(editorScroll);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b ->
                runMutation(() -> {
                    if (activeDb != null) service.useDatabase(activeDb);
                    return service.replaceById(lastCollection, id, editor.getText()) + " document(s) updated";
                }));
    }

    private void deleteDocument(org.bson.Document doc) {
        if (doc == null || lastCollection == null) return;
        Object id = doc.get("_id");
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Delete this document (_id " + id + ")?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        if (getScene() != null) confirm.initOwner(getScene().getWindow());
        confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b ->
                runMutation(() -> {
                    if (activeDb != null) service.useDatabase(activeDb);
                    return service.deleteById(lastCollection, id) + " document(s) deleted";
                }));
    }

    /** Runs a write, reports it, and refreshes the result grid by re-running the query. */
    private void runMutation(java.util.concurrent.Callable<String> action) {
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception { return action.call(); }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText(task.getValue());
            logger.accept("Mongo: " + task.getValue());
            run(); // refresh the current view
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("✖ " + task.getException().getMessage());
        });
        runBg(task);
    }

    private static final String[] STAGE_OPS = {
            "$match", "$group", "$project", "$sort", "$limit", "$skip",
            "$unwind", "$count", "$lookup", "$addFields", "$set", "$facet"
    };

    /** Visual aggregation pipeline builder: add/remove stages, then run (or load into the editor). */
    private void openPipelineBuilder() {
        Dialog<ButtonType> d = new Dialog<>();
        if (getScene() != null) d.initOwner(getScene().getWindow());
        d.setTitle("Aggregation pipeline builder");
        d.setHeaderText("Add stages in order. Each runs on the selected collection.");
        ButtonType runType = new ButtonType("Run", ButtonBar.ButtonData.OK_DONE);
        ButtonType loadType = new ButtonType("Load into editor", ButtonBar.ButtonData.OTHER);
        d.getDialogPane().getButtonTypes().addAll(runType, loadType, ButtonType.CANCEL);
        d.setOnShown(ev -> { if (d.getDialogPane().getScene() != null) com.nexuslink.ui.theme.ThemeManager.get().register(d.getDialogPane().getScene()); });

        java.util.List<StageRow> stages = new java.util.ArrayList<>();
        VBox stagesBox = new VBox(8);
        Runnable addStage = () -> {
            StageRow sr = new StageRow();
            stages.add(sr);
            Button remove = new Button("✕");
            remove.getStyleClass().add("btn-secondary");
            remove.setOnAction(e -> { stages.remove(sr); stagesBox.getChildren().remove(sr.node); });
            sr.node = new VBox(4, new HBox(8, sr.op, remove), sr.body);
            ((HBox) sr.node.getChildren().get(0)).setAlignment(Pos.CENTER_LEFT);
            stagesBox.getChildren().add(sr.node);
        };
        addStage.run();
        Button addBtn = new Button("+ Add stage");
        addBtn.getStyleClass().add("btn-secondary");
        addBtn.setOnAction(e -> addStage.run());

        ScrollPane scroll = new ScrollPane(stagesBox);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(560, 380);
        VBox content = new VBox(8, scroll, addBtn);
        content.setPadding(new Insets(10, 4, 4, 4));
        d.getDialogPane().setContent(content);

        java.util.Optional<ButtonType> result = d.showAndWait();
        if (result.isEmpty() || result.get() == ButtonType.CANCEL) return;
        String pipeline = buildPipeline(stages);
        modeCombo.setValue("aggregate");
        queryEditor.setText(pipeline);
        if (result.get() == runType) run();
    }

    private String buildPipeline(java.util.List<StageRow> stages) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < stages.size(); i++) {
            StageRow s = stages.get(i);
            String body = s.body.getText().isBlank() ? "{}" : s.body.getText().trim();
            sb.append("  { \"").append(s.op.getValue()).append("\": ").append(body).append(" }");
            if (i < stages.size() - 1) sb.append(',');
            sb.append('\n');
        }
        return sb.append(']').toString();
    }

    /** One pipeline stage in the builder. */
    private static final class StageRow {
        final ComboBox<String> op = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(STAGE_OPS));
        final TextArea body = new TextArea();
        VBox node;
        StageRow() {
            op.setValue("$match");
            body.getStyleClass().add("code-area");
            body.setPrefRowCount(3);
            body.setPromptText("{ } stage body, e.g. { \"status\": \"active\" }");
        }
    }

    private void exportResults(boolean csv) {
        if (lastDocs.isEmpty()) { statusLabel.setText("Run a find/SQL query first"); return; }
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Export " + (csv ? "CSV" : "JSON"));
        chooser.setInitialFileName("mongo-export." + (csv ? "csv" : "json"));
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                csv ? "CSV" : "JSON", csv ? "*.csv" : "*.json"));
        var file = chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) return;
        try {
            String content = csv ? MongoService.toCsv(lastDocs) : MongoService.toJsonArray(lastDocs);
            java.nio.file.Files.writeString(file.toPath(), content);
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Exported " + lastDocs.size() + " doc(s) → " + file.getName());
            logger.accept("Mongo export → " + file.getAbsolutePath());
        } catch (Exception ex) {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Export failed: " + ex.getMessage());
        }
    }

    private void showDiagram() {
        if (activeDb == null) { statusLabel.setText("Select a database in the tree first"); return; }
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Inferring schema…");
        String db = activeDb;
        Task<String> task = new Task<>() {
            @Override protected String call() { return service.inferDiagram(db, 200); }
        };
        task.setOnSucceeded(e -> {
            statusLabel.setText("Schema diagram ready");
            com.nexuslink.ui.markdown.DiagramView view = new com.nexuslink.ui.markdown.DiagramView();
            view.setDiagram(task.getValue());
            javafx.scene.Scene scene = new javafx.scene.Scene(view, 960, 720);
            com.nexuslink.ui.theme.ThemeManager.get().register(scene);
            javafx.stage.Stage stage = new javafx.stage.Stage();
            if (getScene() != null) stage.initOwner(getScene().getWindow());
            stage.setTitle("MongoDB schema — " + db + "  (scroll to zoom, drag to pan)");
            stage.setScene(scene);
            stage.show();
            logger.accept("Mongo schema diagram generated for " + db);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("✖ " + task.getException().getMessage());
        });
        runBg(task);
    }

    private void createCollectionDialog() {
        if (activeDb == null) { statusLabel.setText("Select a database in the tree first"); return; }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Create collection");
        dialog.setHeaderText("New collection in database '" + activeDb + "'");
        dialog.setContentText("Name:");
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        dialog.showAndWait().ifPresent(name -> {
            if (name.isBlank()) return;
            runStructure(() -> { service.useDatabase(activeDb); service.createCollection(name.trim()); return "Created collection " + name.trim(); });
        });
    }

    private void createIndexDialog() {
        if (activeCollection == null) { statusLabel.setText("Select a collection in the tree first"); return; }
        Dialog<ButtonType> d = new Dialog<>();
        if (getScene() != null) d.initOwner(getScene().getWindow());
        d.setTitle("Create index");
        d.setHeaderText("New index on '" + activeCollection + "'");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setOnShown(ev -> { if (d.getDialogPane().getScene() != null) com.nexuslink.ui.theme.ThemeManager.get().register(d.getDialogPane().getScene()); });
        TextField keys = new TextField("{ \"field\": 1 }");
        CheckBox unique = new CheckBox("Unique");
        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(12, 4, 4, 4));
        g.addRow(0, new Label("Keys (JSON):"), keys);
        g.add(unique, 1, 1);
        d.getDialogPane().setContent(g);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            if (keys.getText().isBlank()) return;
            runStructure(() -> {
                if (activeDb != null) service.useDatabase(activeDb);
                return "Created index " + service.createIndex(activeCollection, keys.getText().trim(), unique.isSelected());
            });
        });
    }

    private void dropIndexDialog() {
        if (activeCollection == null) { statusLabel.setText("Select a collection in the tree first"); return; }
        List<String> names;
        try {
            if (activeDb != null) service.useDatabase(activeDb);
            names = service.indexNames(activeCollection).stream().filter(n -> !"_id_".equals(n)).toList();
        } catch (Exception ex) {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("✖ " + ex.getMessage());
            return;
        }
        if (names.isEmpty()) { statusLabel.setText("'" + activeCollection + "' has no droppable indexes"); return; }
        ChoiceDialog<String> dialog = new ChoiceDialog<>(names.get(0), names);
        dialog.setTitle("Drop index");
        dialog.setHeaderText("Drop an index on '" + activeCollection + "'");
        dialog.setContentText("Index:");
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        dialog.showAndWait().ifPresent(name -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Drop index '" + name + "' on " + activeCollection + "? This cannot be undone.",
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.initOwner(getScene() == null ? null : getScene().getWindow());
            confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> runStructure(() -> {
                if (activeDb != null) service.useDatabase(activeDb);
                service.dropIndex(activeCollection, name);
                return "Dropped index " + name;
            }));
        });
    }

    /**
     * Authentication panel — builds a SCRAM/TLS connection string from fields (so users don't have
     * to hand-write URIs), and, when connected, shows who we are plus the users on a database
     * with create / grant / drop.
     */
    private void openAuthPanel() {
        Dialog<ButtonType> d = new Dialog<>();
        if (getScene() != null) d.initOwner(getScene().getWindow());
        d.setTitle("MongoDB authentication");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        d.setOnShown(ev -> { if (d.getDialogPane().getScene() != null) com.nexuslink.ui.theme.ThemeManager.get().register(d.getDialogPane().getScene()); });

        // --- Build a connection string ---
        TextField host = new TextField("localhost");
        TextField port = new TextField("27017");
        TextField user = new TextField();
        PasswordField pass = new PasswordField();
        TextField authSource = new TextField("admin");
        ComboBox<String> mech = new ComboBox<>();
        mech.getItems().addAll("(default)", "SCRAM-SHA-256", "SCRAM-SHA-1", "MONGODB-X509", "PLAIN", "GSSAPI");
        mech.setValue("(default)");
        CheckBox tls = new CheckBox("TLS");
        CheckBox srv = new CheckBox("SRV (mongodb+srv://)");
        Label preview = new Label();
        preview.getStyleClass().add("meta-label");
        preview.setWrapText(true);
        Runnable refresh = () -> preview.setText(buildUri(host.getText(), port.getText(), user.getText(),
                pass.getText(), authSource.getText(), mech.getValue(), tls.isSelected(), srv.isSelected(), true));
        for (TextField f : List.of(host, port, user, pass, authSource)) f.textProperty().addListener((o, a, b) -> refresh.run());
        mech.valueProperty().addListener((o, a, b) -> refresh.run());
        tls.selectedProperty().addListener((o, a, b) -> refresh.run());
        srv.selectedProperty().addListener((o, a, b) -> { port.setDisable(b); refresh.run(); });
        refresh.run();

        Button apply = new Button("Use this connection string");
        apply.getStyleClass().add("btn-primary");
        apply.setOnAction(e -> {
            connField.setText(buildUri(host.getText(), port.getText(), user.getText(), pass.getText(),
                    authSource.getText(), mech.getValue(), tls.isSelected(), srv.isSelected(), false));
            statusLabel.getStyleClass().setAll("meta-label");
            statusLabel.setText("Connection string updated — press Connect");
        });

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(12, 4, 8, 4));
        g.addRow(0, new Label("Host:"), host, new Label("Port:"), port);
        g.addRow(1, new Label("Username:"), user, new Label("Password:"), pass);
        g.addRow(2, new Label("Auth source:"), authSource, new Label("Mechanism:"), mech);
        g.addRow(3, tls, srv);
        g.add(preview, 0, 4, 4, 1);
        g.add(apply, 0, 5, 4, 1);

        // --- Live: who am I + users on a database ---
        TextField userDb = new TextField(activeDb == null ? "admin" : activeDb);
        ListView<String> users = new ListView<>();
        users.setPrefHeight(160);
        Label who = new Label("Not connected");
        who.getStyleClass().add("meta-label");
        who.setWrapText(true);
        Runnable reload = () -> {
            if (!service.isConnected()) { who.setText("Not connected"); users.getItems().clear(); return; }
            try {
                StringBuilder sb = new StringBuilder();
                service.authStatus().forEach((k, v) -> sb.append(k).append(": ").append(v).append("\n"));
                who.setText(sb.toString().trim());
                users.getItems().setAll(service.listUsers(userDb.getText().trim()));
            } catch (Exception ex) {
                who.setText("✖ " + ex.getMessage());
                users.getItems().clear();
            }
        };
        Button refreshUsers = new Button("Refresh");
        refreshUsers.getStyleClass().add("btn-secondary");
        refreshUsers.setOnAction(e -> reload.run());

        Button addUser = new Button("Create user…");
        addUser.getStyleClass().add("btn-secondary");
        addUser.setOnAction(e -> userDialog(userDb.getText().trim(), null, reload));

        Button editRoles = new Button("Edit roles…");
        editRoles.getStyleClass().add("btn-secondary");
        editRoles.setOnAction(e -> {
            String sel = userName(users.getSelectionModel().getSelectedItem());
            if (sel != null) userDialog(userDb.getText().trim(), sel, reload);
        });

        Button dropUser = new Button("Drop user");
        dropUser.getStyleClass().add("btn-secondary");
        dropUser.setOnAction(e -> {
            String sel = userName(users.getSelectionModel().getSelectedItem());
            if (sel == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Drop user '" + sel + "' on " + userDb.getText().trim() + "?", ButtonType.OK, ButtonType.CANCEL);
            confirm.initOwner(d.getDialogPane().getScene() == null ? null : d.getDialogPane().getScene().getWindow());
            confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
                try {
                    service.dropUser(userDb.getText().trim(), sel);
                    logger.accept("Mongo: dropped user " + sel);
                    reload.run();
                } catch (Exception ex) {
                    who.setText("✖ " + ex.getMessage());
                }
            });
        });
        userDb.setOnAction(e -> reload.run());
        reload.run();

        HBox userBar = new HBox(8, new Label("Database:"), userDb, refreshUsers, addUser, editRoles, dropUser);
        userBar.setAlignment(Pos.CENTER_LEFT);
        VBox live = new VBox(8, who, new Separator(), userBar, users);
        live.setPadding(new Insets(12, 4, 4, 4));

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab t1 = new Tab("Connection string", g);
        Tab t2 = new Tab("Users & roles", live);
        tabs.getTabs().addAll(t1, t2);
        tabs.setPrefWidth(720);
        d.getDialogPane().setContent(tabs);
        d.showAndWait();
    }

    /** "alice  —  readWrite@app" → "alice". */
    private static String userName(String row) {
        if (row == null || row.isBlank()) return null;
        int i = row.indexOf("  —  ");
        return (i < 0 ? row : row.substring(0, i)).trim();
    }

    private void userDialog(String database, String existing, Runnable onDone) {
        Dialog<ButtonType> d = new Dialog<>();
        if (getScene() != null) d.initOwner(getScene().getWindow());
        d.setTitle(existing == null ? "Create user" : "Edit roles");
        d.setHeaderText((existing == null ? "New user on '" : "Roles for '" + existing + "' on '") + database + "'");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setOnShown(ev -> { if (d.getDialogPane().getScene() != null) com.nexuslink.ui.theme.ThemeManager.get().register(d.getDialogPane().getScene()); });
        TextField name = new TextField(existing == null ? "" : existing);
        name.setDisable(existing != null);
        PasswordField pwd = new PasswordField();
        TextField roles = new TextField("readWrite");
        roles.setPromptText("readWrite, read@other-db, clusterMonitor@admin");
        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(12, 4, 4, 4));
        g.addRow(0, new Label("User:"), name);
        if (existing == null) g.addRow(1, new Label("Password:"), pwd);
        g.addRow(2, new Label("Roles:"), roles);
        d.getDialogPane().setContent(g);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            if (name.getText().isBlank()) return;
            runStructure(() -> {
                if (existing == null) {
                    service.createUser(database, name.getText().trim(), pwd.getText(), roles.getText());
                    return "Created user " + name.getText().trim() + " on " + database;
                }
                service.grantRoles(database, existing, roles.getText());
                return "Updated roles for " + existing;
            });
            onDone.run();
        });
    }

    /** Assembles a connection string; {@code maskPassword} renders it for on-screen preview. */
    static String buildUri(String host, String port, String user, String pass, String authSource,
                                   String mechanism, boolean tls, boolean srv, boolean maskPassword) {
        String h = host == null || host.isBlank() ? "localhost" : host.trim();
        StringBuilder sb = new StringBuilder(srv ? "mongodb+srv://" : "mongodb://");
        if (user != null && !user.isBlank()) {
            sb.append(enc(user.trim())).append(':');
            sb.append(maskPassword ? "••••" : enc(pass == null ? "" : pass)).append('@');
        }
        sb.append(h);
        if (!srv && port != null && !port.isBlank()) sb.append(':').append(port.trim());
        sb.append('/');
        List<String> opts = new java.util.ArrayList<>();
        if (authSource != null && !authSource.isBlank()) opts.add("authSource=" + enc(authSource.trim()));
        if (mechanism != null && !mechanism.startsWith("(")) opts.add("authMechanism=" + mechanism);
        if (tls && !srv) opts.add("tls=true");
        if (!opts.isEmpty()) sb.append('?').append(String.join("&", opts));
        return sb.toString();
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void runStructure(java.util.concurrent.Callable<String> action) {
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Working…");
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception { return action.call(); }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText(task.getValue());
            logger.accept("Mongo: " + task.getValue());
            explorer.load();
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("✖ " + task.getException().getMessage());
        });
        runBg(task);
    }

    private void saveCurrent() {
        String conn = connField.getText().trim();
        if (conn.isEmpty()) { statusLabel.setText("Enter a connection string before saving"); return; }
        TextInputDialog dialog = new TextInputDialog(conn);
        dialog.setTitle("Save connection");
        dialog.setHeaderText("Save this MongoDB connection for later");
        dialog.setContentText("Name:");
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        dialog.showAndWait().ifPresent(name -> {
            if (name.isBlank()) return;
            ConnectionProfile p = new ConnectionProfile(name.trim(), ConnectionProfile.Protocol.MONGO, conn)
                    .withAuth(AuthMethod.CONNECTION_STRING);
            onSave.accept(p);
            logger.accept("Saved MongoDB connection: " + name.trim());
        });
    }

    private VBox buildConnectionBar() {
        connField.getStyleClass().add("nl-field");
        HBox.setHgrow(connField, Priority.ALWAYS);
        connField.setPromptText("mongodb://host:27017  or  mongodb+srv://user:pass@cluster/…");

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());

        Button saveBtn = new Button("Save");
        saveBtn.getStyleClass().add("btn-secondary");
        saveBtn.setOnAction(e -> saveCurrent());

        Button diagramBtn = new Button("Diagram");
        diagramBtn.getStyleClass().add("btn-secondary");
        diagramBtn.setTooltip(new Tooltip("Infer a schema diagram from sampled documents"));
        diagramBtn.setOnAction(e -> showDiagram());

        Button pipelineBtn = new Button("Pipeline…");
        pipelineBtn.getStyleClass().add("btn-secondary");
        pipelineBtn.setTooltip(new Tooltip("Build an aggregation pipeline stage by stage"));
        pipelineBtn.setOnAction(e -> openPipelineBuilder());

        MenuButton exportBtn = new MenuButton("Export");
        exportBtn.getStyleClass().add("btn-secondary");
        MenuItem exportJson = new MenuItem("Export JSON…");
        exportJson.setOnAction(e -> exportResults(false));
        MenuItem exportCsv = new MenuItem("Export CSV…");
        exportCsv.setOnAction(e -> exportResults(true));
        exportBtn.getItems().addAll(exportJson, exportCsv);

        MenuButton structureBtn = new MenuButton("Structure");
        structureBtn.getStyleClass().add("btn-secondary");
        MenuItem createColl = new MenuItem("Create Collection…");
        createColl.setOnAction(e -> createCollectionDialog());
        MenuItem createIndex = new MenuItem("Create Index…");
        createIndex.setOnAction(e -> createIndexDialog());
        MenuItem dropIndex = new MenuItem("Drop Index…");
        dropIndex.setOnAction(e -> dropIndexDialog());
        structureBtn.getItems().addAll(createColl, createIndex, dropIndex);

        Button authBtn = new Button("Auth…");
        authBtn.getStyleClass().add("btn-secondary");
        authBtn.setTooltip(new Tooltip("Build an authenticated connection string; manage users and roles"));
        authBtn.setOnAction(e -> openAuthPanel());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("databases"));

        Label lbl = new Label("Connection:");
        lbl.getStyleClass().add("meta-label");
        HBox row = new HBox(8, lbl, connField, connectBtn, saveBtn, diagramBtn, pipelineBtn, exportBtn, structureBtn, authBtn, helpBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row, statusRow);
    }

    private SplitPane buildBody() {
        // Left: live object explorer (databases → collections → indexes)
        explorer.setMinWidth(240);
        explorer.setOnSelect(this::onExplorerSelect);
        explorer.setOnActivate(node -> {
            if (node.kind() == ResourceNode.Kind.COLLECTION) {
                onExplorerSelect(node);
                modeCombo.setValue("find");
                queryEditor.setText("{}");
                run();
            }
        });

        // Right: query editor + result
        modeCombo.getItems().addAll("find", "sql", "aggregate", "explain", "insertOne", "updateMany", "deleteMany");
        modeCombo.setValue("find");
        modeCombo.valueProperty().addListener((o, ov, m) -> updateEditorHint(m));

        limitField.setPrefWidth(70);
        Label limitLbl = new Label("limit:");
        limitLbl.getStyleClass().add("meta-label");

        Button runBtn = new Button("Run  (Ctrl+Enter)");
        runBtn.getStyleClass().add("btn-primary");
        runBtn.setOnAction(e -> run());

        viewCombo.getItems().addAll("JSON", "Table", "Schema");
        viewCombo.setValue("JSON");
        viewCombo.valueProperty().addListener((o, ov, nv) -> renderView());
        Label viewLbl = new Label("View:");
        viewLbl.getStyleClass().add("meta-label");

        recentBtn.getStyleClass().add("btn-secondary");
        recentBtn.setDisable(true);
        HBox controls = new HBox(8, new Label("Operation:"), modeCombo, limitLbl, limitField, runBtn,
                recentBtn, viewLbl, viewCombo, resultStatus);
        ((Label) controls.getChildren().get(0)).getStyleClass().add("meta-label");
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(6));
        resultStatus.getStyleClass().add("meta-label");

        queryEditor.getStyleClass().add("code-area");
        queryEditor.setPrefRowCount(5);
        queryEditor.setOnKeyPressed(e -> {
            if (e.isShortcutDown() && e.getCode() == javafx.scene.input.KeyCode.ENTER) run();
        });
        updateEditorHint("find");

        com.nexuslink.ui.util.JsonView.setText(resultArea, "");
        docTable.getStyleClass().add("details-table");
        docTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        docTable.setPlaceholder(new Label("Run a find/SQL query, then switch to Table view"));
        docTable.setRowFactory(tv -> {
            TableRow<org.bson.Document> rowUI = new TableRow<>();
            MenuItem edit = new MenuItem("Edit document…");
            edit.setOnAction(e -> editDocument(rowUI.getItem()));
            MenuItem del = new MenuItem("Delete document");
            del.setOnAction(e -> deleteDocument(rowUI.getItem()));
            ContextMenu menu = new ContextMenu(edit, del);
            rowUI.contextMenuProperty().bind(javafx.beans.binding.Bindings
                    .when(rowUI.emptyProperty()).then((ContextMenu) null).otherwise(menu));
            rowUI.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !rowUI.isEmpty()) editDocument(rowUI.getItem());
            });
            return rowUI;
        });
        buildSchemaTable();

        StackPane resultStack = new StackPane(resultScroll, docTable, schemaTable);
        docTable.setVisible(false);
        schemaTable.setVisible(false);

        VBox right = new VBox(6, controls, queryEditor, resultStack);
        right.setPadding(new Insets(8));
        VBox.setVgrow(resultStack, Priority.ALWAYS);

        SplitPane sp = new SplitPane(explorer, right);
        sp.setDividerPositions(0.28);
        return sp;
    }

    private void onExplorerSelect(ResourceNode node) {
        switch (node.kind()) {
            case DATABASE -> {
                activeDb = node.label();
                activeCollection = null;
                service.useDatabase(activeDb);
            }
            case COLLECTION -> {
                String path = node.id().substring("coll:".length());   // db.collection
                int dot = path.indexOf('.');
                activeDb = path.substring(0, dot);
                activeCollection = path.substring(dot + 1);
                service.useDatabase(activeDb);
            }
            default -> { /* index / other: keep current active collection */ }
        }
    }

    private void updateEditorHint(String mode) {
        queryEditor.setPromptText(switch (mode) {
            case "find" -> "{ }  — Mongo filter, e.g. { \"role\": \"developer\" }";
            case "sql" -> "SELECT * FROM <collection> WHERE role = 'developer' ORDER BY name LIMIT 20";
            case "aggregate" -> "[ { \"$group\": { \"_id\": \"$role\", \"n\": { \"$sum\": 1 } } } ]";
            case "insertOne" -> "{ \"name\": \"Alice\", \"role\": \"admin\" }  — document to insert";
            case "updateMany" -> "filter ||| update    e.g.  { \"name\":\"Alice\" } ||| { \"$set\": { \"role\":\"x\" } }";
            case "deleteMany" -> "{ }  — filter for documents to delete";
            default -> "";
        });
        String t = queryEditor.getText().trim();
        boolean isDefault = t.isBlank() || t.equals("{}") || t.equals("[]") || t.toUpperCase().startsWith("SELECT");
        if (isDefault) {
            queryEditor.setText(switch (mode) {
                case "aggregate" -> "[]";
                case "sql" -> "SELECT * FROM ";
                default -> "{}";
            });
        }
    }

    private void connect() {
        connectBtn.setDisable(true);
        statusLabel.setText("Connecting…");
        String conn = Env.resolve(connField.getText().trim());   // resolve ${VAR} against active environment
        logger.accept("Mongo connect → " + conn.replaceAll(":[^:@/]+@", ":***@"));
        Task<List<String>> task = new Task<>() {
            @Override protected List<String> call() { return service.connect(conn); }
        };
        task.setOnSucceeded(e -> {
            List<String> dbs = task.getValue();
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + dbs.size() + " database(s)");
            logger.accept("Mongo connected — " + dbs.size() + " dbs");
            explorer.setExplorer(explorerModel);
            explorer.load();
            connectBtn.setDisable(false);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            logger.accept("Mongo connect FAILED: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        runBg(task);
    }

    private void run() {
        String mode = modeCombo.getValue();
        String body = Env.resolve(queryEditor.getText().trim());   // resolve ${VAR} in the query/pipeline
        // SQL mode parses its own collection from the FROM clause; it only needs a database.
        if ("sql".equals(mode)) {
            if (activeDb == null) { resultStatus.setText("Select a database in the tree first"); return; }
            service.useDatabase(activeDb);
        } else if (activeCollection == null) {
            resultStatus.setText("Select a collection in the tree first");
            return;
        } else if (activeDb != null) {
            service.useDatabase(activeDb);
        }
        String collection = activeCollection;
        int limit = parseLimit();
        resultStatus.setText("Running " + mode + "…");
        logger.accept("Mongo " + mode + (collection == null ? "" : " → " + collection));

        Task<String> task = new Task<>() {
            @Override protected String call() {
                return switch (mode) {
                    case "find" -> renderDocs(service.find(collection, body, limit));
                    case "sql" -> renderDocs(service.executeSql(body));
                    case "explain" -> service.explain(collection, body);
                    case "aggregate" -> renderDocs(service.aggregate(collection, body));
                    case "insertOne" -> "Inserted _id: " + service.insertOne(collection, body);
                    case "updateMany" -> {
                        String[] parts = body.split("\\|\\|\\|", 2);
                        if (parts.length != 2) yield "updateMany needs:  filter ||| update";
                        yield service.updateMany(collection, parts[0].trim(), parts[1].trim()) + " document(s) modified";
                    }
                    case "deleteMany" -> service.deleteMany(collection, body) + " document(s) deleted";
                    default -> "";
                };
            }
        };
        lastCollection = "find".equals(mode) ? collection : null;   // edit/delete only on find results
        boolean docMode = mode.equals("find") || mode.equals("sql") || mode.equals("aggregate");
        task.setOnSucceeded(e -> {
            com.nexuslink.ui.util.JsonView.setText(resultArea, task.getValue());
            resultStatus.getStyleClass().setAll("meta-label");
            resultStatus.setText("ok");
            if (docMode) renderView();      // refresh Table/Schema/JSON for the new docs
            else { lastDocs.clear(); showNode(resultScroll); }
            if (docMode || mode.equals("explain")) rememberQuery(mode, body);
        });
        task.setOnFailed(e -> {
            resultStatus.getStyleClass().setAll("status-err");
            resultStatus.setText("✖ " + task.getException().getMessage());
            com.nexuslink.ui.util.JsonView.setText(resultArea, "Error: " + task.getException().getMessage());
            showNode(resultScroll);
        });
        runBg(task);
    }

    private String renderDocs(MongoQueryResult r) {
        if (!r.success()) throw new RuntimeException(r.error());
        Platform.runLater(() -> resultStatus.setText(r.count() + " doc(s) · " + r.durationMs() + " ms"));
        synchronized (lastDocs) {
            lastDocs.clear();
            for (String json : r.documents()) {
                try { lastDocs.add(org.bson.Document.parse(json)); } catch (Exception ignored) { }
            }
        }
        return r.documents().isEmpty() ? "(no documents)" : String.join("\n", r.documents());
    }

    private void rememberQuery(String mode, String body) {
        if (body == null || body.isBlank()) return;
        recentQueries.removeIf(q -> q[0].equals(mode) && q[1].equals(body));
        recentQueries.addFirst(new String[]{mode, body});
        while (recentQueries.size() > 15) recentQueries.removeLast();
        recentBtn.getItems().clear();
        for (String[] q : recentQueries) {
            String oneLine = q[1].replaceAll("\\s+", " ").trim();
            MenuItem mi = new MenuItem(q[0] + ":  " + (oneLine.length() > 60 ? oneLine.substring(0, 60) + "…" : oneLine));
            mi.setOnAction(e -> { modeCombo.setValue(q[0]); queryEditor.setText(q[1]); });
            recentBtn.getItems().add(mi);
        }
        recentBtn.setDisable(recentQueries.isEmpty());
    }

    // ---- Compass-like result views ----

    private void renderView() {
        String v = viewCombo.getValue();
        switch (v == null ? "JSON" : v) {
            case "Table" -> { buildDocTable(); showNode(docTable); }
            case "Schema" -> { buildSchema(); showNode(schemaTable); }
            default -> {
                if (!lastDocs.isEmpty()) {
                    org.bson.json.JsonWriterSettings pretty =
                            org.bson.json.JsonWriterSettings.builder().indent(true).build();
                    java.util.List<String> json = new java.util.ArrayList<>();
                    for (org.bson.Document d : lastDocs) json.add(d.toJson(pretty));
                    com.nexuslink.ui.util.JsonView.setText(resultArea, String.join("\n", json));
                }
                showNode(resultScroll);
            }
        }
    }

    private void showNode(javafx.scene.Node visible) {
        resultScroll.setVisible(visible == resultScroll);
        docTable.setVisible(visible == docTable);
        schemaTable.setVisible(visible == schemaTable);
    }

    private void buildDocTable() {
        docTable.getColumns().clear();
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (org.bson.Document d : lastDocs) keys.addAll(d.keySet());
        for (String k : keys) {
            TableColumn<org.bson.Document, String> col = new TableColumn<>(k);
            col.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(cellString(cd.getValue().get(k))));
            col.setPrefWidth(150);
            docTable.getColumns().add(col);
        }
        docTable.setItems(javafx.collections.FXCollections.observableArrayList(lastDocs));
    }

    private void buildSchemaTable() {
        schemaTable.getStyleClass().add("details-table");
        schemaTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        schemaTable.setPlaceholder(new Label("Run a find/SQL query, then switch to Schema view"));
        String[] heads = {"Field", "Type(s)", "Count", "Present"};
        for (int i = 0; i < heads.length; i++) {
            final int idx = i;
            TableColumn<String[], String> c = new TableColumn<>(heads[i]);
            c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                    idx < cd.getValue().length ? cd.getValue()[idx] : ""));
            schemaTable.getColumns().add(c);
        }
    }

    private void buildSchema() {
        java.util.LinkedHashMap<String, java.util.TreeSet<String>> types = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (org.bson.Document d : lastDocs) {
            for (String k : d.keySet()) {
                types.computeIfAbsent(k, x -> new java.util.TreeSet<>()).add(typeName(d.get(k)));
                counts.merge(k, 1, Integer::sum);
            }
        }
        int total = lastDocs.size();
        var rows = javafx.collections.FXCollections.<String[]>observableArrayList();
        for (String k : types.keySet()) {
            int cnt = counts.get(k);
            rows.add(new String[]{k, String.join(", ", types.get(k)), String.valueOf(cnt),
                    total == 0 ? "" : Math.round(100.0 * cnt / total) + "%"});
        }
        schemaTable.setItems(rows);
    }

    private static String cellString(Object v) {
        if (v == null) return "null";
        if (v instanceof org.bson.Document d) return d.toJson();
        if (v instanceof java.util.List<?> l) return l.toString();
        return v.toString();
    }

    private static String typeName(Object v) {
        if (v == null) return "null";
        if (v instanceof org.bson.Document d) {
            if (d.containsKey("$oid")) return "objectId";
            if (d.containsKey("$date")) return "date";
            if (d.containsKey("$numberDecimal")) return "decimal";
            return "object";
        }
        if (v instanceof String) return "string";
        if (v instanceof Integer) return "int";
        if (v instanceof Long) return "long";
        if (v instanceof Double) return "double";
        if (v instanceof Boolean) return "bool";
        if (v instanceof java.util.List) return "array";
        return v.getClass().getSimpleName().toLowerCase();
    }

    private int parseLimit() {
        try { return Math.max(1, Integer.parseInt(limitField.getText().trim())); }
        catch (NumberFormatException e) { return 100; }
    }

    private void runBg(Task<?> task) {
        Thread t = new Thread(task, "mongo-task");
        t.setDaemon(true);
        t.start();
    }
}
